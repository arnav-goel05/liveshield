package com.liveshield.vision.pii;

import com.google.i18n.phonenumbers.PhoneNumberMatch;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.liveshield.privacy.model.FindingCategory;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Payload-free boundary for deterministic structured-text and configured-watchlist validation.
 *
 * <p>The validator is stateless. Input text remains caller-owned and matches contain only a
 * non-sensitive category plus source offsets.</p>
 */
public final class StructuredPiiValidator {
    /**
     * Returns category and source offsets only; implementations must not retain recognized text.
     */
    public List<Match> validate(
            String recognizedText,
            String defaultRegionCode,
            Set<String> normalizedWatchlistTerms) {
        Objects.requireNonNull(recognizedText, "recognizedText");
        Objects.requireNonNull(defaultRegionCode, "defaultRegionCode");
        Objects.requireNonNull(normalizedWatchlistTerms, "normalizedWatchlistTerms");
        requireCodePointBound(recognizedText, 16_384, "Recognized text");
        if (normalizedWatchlistTerms.size() > 32) {
            throw new IllegalArgumentException("At most 32 watchlist terms are supported");
        }
        String region = defaultRegionCode.toUpperCase(Locale.ROOT);
        PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
        if (region.length() != 2 || !phoneUtil.getSupportedRegions().contains(region)) {
            throw new IllegalArgumentException("Unsupported default phone region");
        }

        NormalizedText normalized = NormalizedText.from(recognizedText);
        LinkedHashSet<Match> matches = new LinkedHashSet<>();
        addEmailMatches(normalized, matches);
        addCardMatches(normalized, matches);
        addPhoneMatches(normalized, region, phoneUtil, matches);
        addOtpMatches(normalized, matches);
        addWatchlistMatches(normalized, normalizedWatchlistTerms, matches);
        if (matches.size() > 256) {
            throw new IllegalStateException("Structured PII match limit exceeded");
        }
        ArrayList<Match> ordered = new ArrayList<>(matches);
        ordered.sort(Comparator.comparingInt(Match::startOffset)
                .thenComparingInt(Match::endOffset)
                .thenComparing(match -> match.category().name()));
        return List.copyOf(ordered);
    }

    /** Matches only creator-entered session terms; automatic PII categories remain disabled. */
    public List<Match> validateWatchlistOnly(
            String recognizedText, Set<String> normalizedWatchlistTerms) {
        Objects.requireNonNull(recognizedText, "recognizedText");
        Objects.requireNonNull(normalizedWatchlistTerms, "normalizedWatchlistTerms");
        requireCodePointBound(recognizedText, 16_384, "Recognized text");
        if (normalizedWatchlistTerms.size() > 32) {
            throw new IllegalArgumentException("At most 32 watchlist terms are supported");
        }
        NormalizedText normalized = NormalizedText.from(recognizedText);
        LinkedHashSet<Match> matches = new LinkedHashSet<>();
        addWatchlistMatches(normalized, normalizedWatchlistTerms, matches);
        ArrayList<Match> ordered = new ArrayList<>(matches);
        ordered.sort(Comparator.comparingInt(Match::startOffset)
                .thenComparingInt(Match::endOffset));
        return List.copyOf(ordered);
    }

    private static void addEmailMatches(NormalizedText text, Set<Match> output) {
        Pattern pattern = Pattern.compile(
                "(?<![\\p{L}\\p{N}._%+\\-])"
                        + "([\\p{L}\\p{N}!#$%&'*+/=?^_`{|}~.-]{1,64}"
                        + "@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
                        + "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+)"
                        + "(?![\\p{L}\\p{N}._%+\\-])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Matcher matcher = pattern.matcher(text.value());
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (isStrictEmail(candidate)) {
                addMatch(output, text, FindingCategory.AUTO_EMAIL,
                        matcher.start(1), matcher.end(1));
            }
        }
    }

    private static boolean isStrictEmail(String candidate) {
        int separator = candidate.lastIndexOf('@');
        String local = candidate.substring(0, separator);
        String domain = candidate.substring(separator + 1);
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..")
                || domain.length() > 253) {
            return false;
        }
        String[] labels = domain.split("\\.", -1);
        String finalLabel = labels[labels.length - 1];
        return finalLabel.length() >= 2
                && finalLabel.length() <= 63
                && containsOnlyLetters(finalLabel);
    }

    private static boolean containsOnlyLetters(String value) {
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isLetter(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static void addPhoneMatches(
            NormalizedText text,
            String region,
            PhoneNumberUtil phoneUtil,
            Set<Match> output) {
        Iterable<PhoneNumberMatch> candidates = phoneUtil.findNumbers(
                text.value(), region, PhoneNumberUtil.Leniency.VALID, 64L);
        for (PhoneNumberMatch candidate : candidates) {
            addMatch(output, text, FindingCategory.AUTO_PHONE,
                    candidate.start(), candidate.end());
        }
    }

    private static void addCardMatches(NormalizedText text, Set<Match> output) {
        Pattern pattern = Pattern.compile(
                "(?<!\\p{Nd})(?:\\p{Nd}[ -]?){12,18}\\p{Nd}(?!\\p{Nd})");
        Matcher matcher = pattern.matcher(text.value());
        while (matcher.find()) {
            String candidate = matcher.group();
            StringBuilder digits = new StringBuilder(candidate.length());
            for (int offset = 0; offset < candidate.length(); ) {
                int codePoint = candidate.codePointAt(offset);
                offset += Character.charCount(codePoint);
                int digit = Character.digit(codePoint, 10);
                if (digit >= 0) {
                    digits.append((char) ('0' + digit));
                }
            }
            if (digits.length() >= 13 && digits.length() <= 19
                    && !allDigitsEqual(digits)
                    && passesLuhn(digits)) {
                addMatch(output, text, FindingCategory.AUTO_CARD,
                        matcher.start(), matcher.end());
            }
        }
    }

    private static boolean allDigitsEqual(CharSequence digits) {
        for (int index = 1; index < digits.length(); index++) {
            if (digits.charAt(index) != digits.charAt(0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean passesLuhn(CharSequence digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int value = digits.charAt(index) - '0';
            if (doubleDigit) {
                value *= 2;
                if (value > 9) {
                    value -= 9;
                }
            }
            sum += value;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    private static void addOtpMatches(NormalizedText text, Set<Match> output) {
        Matcher digits = Pattern.compile("(?<!\\p{Nd})\\p{Nd}{4,8}(?!\\p{Nd})")
                .matcher(text.value());
        while (digits.find()) {
            int beforeStart = Math.max(0, digits.start() - 64);
            int afterEnd = Math.min(text.value().length(), digits.end() + 48);
            String before = text.value().substring(beforeStart, digits.start());
            String after = text.value().substring(digits.end(), afterEnd);
            if (hasOtpContextBefore(before) || hasOtpContextAfter(after)) {
                addMatch(output, text, FindingCategory.AUTO_OTP,
                        digits.start(), digits.end());
            }
        }
    }

    private static boolean hasOtpContextBefore(String context) {
        Pattern pattern = Pattern.compile(
                ".*(?:verification(?: code)?|verify|otp|one[ -]?time(?: code| password)?"
                        + "|passcode|security code|authentication code)"
                        + "(?: (?:is|number|code|pin))?[ :#=\\-]{0,12}$",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return pattern.matcher(context).matches();
    }

    private static boolean hasOtpContextAfter(String context) {
        Pattern pattern = Pattern.compile(
                "^[ :#=\\-]{0,12}(?:is )?(?:your )?"
                        + "(?:verification code|otp|one[ -]?time code|passcode|security code)\\b.*",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return pattern.matcher(context).matches();
    }

    private static void addWatchlistMatches(
            NormalizedText text, Set<String> terms, Set<Match> output) {
        for (String supplied : terms) {
            String term = normalizeWatchlistTerm(supplied);
            int fromIndex = 0;
            while (fromIndex <= text.value().length() - term.length()) {
                int start = text.value().indexOf(term, fromIndex);
                if (start < 0) {
                    break;
                }
                int end = start + term.length();
                if (hasWordBoundaries(text.value(), term, start, end)) {
                    addMatch(output, text, FindingCategory.WATCHLIST_MATCH, start, end);
                }
                fromIndex = start + 1;
            }
        }
    }

    private static String normalizeWatchlistTerm(String supplied) {
        Objects.requireNonNull(supplied, "watchlist term");
        requireCodePointBound(supplied, 64, "Watchlist term");
        String normalized = NormalizedText.from(supplied).value();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Watchlist terms must not be empty");
        }
        return normalized;
    }

    private static boolean hasWordBoundaries(
            String source, String term, int start, int end) {
        int first = term.codePointAt(0);
        if (start > 0 && isWord(first)
                && isWord(source.codePointBefore(start))) {
            return false;
        }
        int last = term.codePointBefore(term.length());
        return end >= source.length() || !isWord(last) || !isWord(source.codePointAt(end));
    }

    private static boolean isWord(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    private static void addMatch(
            Set<Match> output,
            NormalizedText text,
            FindingCategory category,
            int normalizedStart,
            int normalizedEnd) {
        output.add(new Match(
                category,
                text.originalStart(normalizedStart),
                text.originalEnd(normalizedEnd)));
        if (output.size() > 256) {
            throw new IllegalStateException("Structured PII match limit exceeded");
        }
    }

    private static void requireCodePointBound(String value, int maximum, String label) {
        if (value.codePointCount(0, value.length()) > maximum) {
            throw new IllegalArgumentException(label + " exceeds its configured bound");
        }
    }

    /** Immutable non-payload match locating one protected span in the caller-owned source text. */
    public record Match(FindingCategory category, int startOffset, int endOffset) {
        public Match {
            Objects.requireNonNull(category, "category");
            if (category != FindingCategory.AUTO_EMAIL
                    && category != FindingCategory.AUTO_PHONE
                    && category != FindingCategory.AUTO_CARD
                    && category != FindingCategory.AUTO_OTP
                    && category != FindingCategory.WATCHLIST_MATCH) {
                throw new IllegalArgumentException("Unsupported structured PII category");
            }
            if (startOffset < 0 || endOffset <= startOffset) {
                throw new IllegalArgumentException("Match offsets must describe a non-empty span");
            }
        }
    }

    private record NormalizedText(String value, int[] starts, int[] ends) {
        private static NormalizedText from(String source) {
            StringBuilder normalized = new StringBuilder(source.length());
            ArrayList<Integer> starts = new ArrayList<>();
            ArrayList<Integer> ends = new ArrayList<>();
            boolean pendingSpace = false;
            int pendingStart = -1;
            int pendingEnd = -1;
            for (int offset = 0; offset < source.length(); ) {
                int clusterStart = offset;
                offset += Character.charCount(source.codePointAt(offset));
                while (offset < source.length()
                        && isCombiningMark(source.codePointAt(offset))) {
                    offset += Character.charCount(source.codePointAt(offset));
                }
                String cluster = Normalizer.normalize(
                        source.substring(clusterStart, offset), Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT);
                for (int clusterOffset = 0; clusterOffset < cluster.length(); ) {
                    int codePoint = cluster.codePointAt(clusterOffset);
                    clusterOffset += Character.charCount(codePoint);
                    if (Character.isWhitespace(codePoint)) {
                        if (normalized.length() > 0) {
                            pendingSpace = true;
                            if (pendingStart < 0) {
                                pendingStart = clusterStart;
                            }
                            pendingEnd = offset;
                        }
                    } else {
                        if (pendingSpace) {
                            appendMapped(normalized, starts, ends, ' ', pendingStart, pendingEnd);
                            pendingSpace = false;
                            pendingStart = -1;
                            pendingEnd = -1;
                        }
                        appendMapped(normalized, starts, ends, codePoint, clusterStart, offset);
                    }
                }
            }
            return new NormalizedText(
                    normalized.toString(), toArray(starts), toArray(ends));
        }

        private int originalStart(int normalizedOffset) {
            return starts[normalizedOffset];
        }

        private int originalEnd(int normalizedEnd) {
            return ends[normalizedEnd - 1];
        }

        private static boolean isCombiningMark(int codePoint) {
            int type = Character.getType(codePoint);
            return type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK;
        }

        private static void appendMapped(
                StringBuilder destination,
                List<Integer> starts,
                List<Integer> ends,
                int codePoint,
                int sourceStart,
                int sourceEnd) {
            int before = destination.length();
            destination.appendCodePoint(codePoint);
            for (int index = before; index < destination.length(); index++) {
                starts.add(sourceStart);
                ends.add(sourceEnd);
            }
        }

        private static int[] toArray(List<Integer> values) {
            int[] result = new int[values.size()];
            for (int index = 0; index < values.size(); index++) {
                result[index] = values.get(index);
            }
            return result;
        }
    }
}
