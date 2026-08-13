package com.liveshield.vision.pii;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.model.FindingCategory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

/** Tests-first contract for T095's deterministic, session-local structured PII validator. */
public final class StructuredPiiValidatorTest {
    private final StructuredPiiValidator validator = new StructuredPiiValidator();

    @Test
    public void strictEmailMatchesAddressOnlyAndRejectsAdversarialNearMatches() {
        String text = "Contact creator.test+live@example.org for the fictional demo";

        assertSpan(text, "creator.test+live@example.org", FindingCategory.AUTO_EMAIL,
                validate(text, "SG", Set.of()));
        assertNoCategory(validate("broken@example and a..b@example.org", "SG", Set.of()),
                FindingCategory.AUTO_EMAIL);
        assertNoCategory(validate("name@@example.org and @example.org", "SG", Set.of()),
                FindingCategory.AUTO_EMAIL);
    }

    @Test
    public void libphonenumberValidatesInternationalAndRegionSpecificPhones() {
        String international = "Call +65 8123 4567 for the staged fixture";
        String regional = "US callback: (202) 555-0123";

        assertSpan(international, "+65 8123 4567", FindingCategory.AUTO_PHONE,
                validate(international, "SG", Set.of()));
        assertSpan(regional, "(202) 555-0123", FindingCategory.AUTO_PHONE,
                validate(regional, "US", Set.of()));
        assertNoCategory(validate("Reference 12345 or +65 1234 5678", "SG", Set.of()),
                FindingCategory.AUTO_PHONE);
    }

    @Test
    public void paymentCardRequiresNormalizedLuhnAndPlausibleLength() {
        String valid = "Fictional card 4111 1111 1111 1111 expires later";

        assertSpan(valid, "4111 1111 1111 1111", FindingCategory.AUTO_CARD,
                validate(valid, "SG", Set.of()));
        assertNoCategory(validate("Near card 4111 1111 1111 1112", "SG", Set.of()),
                FindingCategory.AUTO_CARD);
        assertNoCategory(validate("Luhn-valid but too short 79927398713", "SG", Set.of()),
                FindingCategory.AUTO_CARD);
    }

    @Test
    public void otpRequiresNearbyVerificationContext() {
        String contextual = "Your verification code is 482913. It expires soon.";

        assertSpan(contextual, "482913", FindingCategory.AUTO_OTP,
                validate(contextual, "SG", Set.of()));
        assertNoCategory(validate("Inventory reference 482913", "SG", Set.of()),
                FindingCategory.AUTO_OTP);
        assertNoCategory(validate("verification code is 42", "SG", Set.of()),
                FindingCategory.AUTO_OTP);
    }

    @Test
    public void exactWatchlistUsesNfkcCaseFoldWhitespaceAndUnicodeBoundaries() {
        String text = "Badge: ＡCMÉ\u00a0SCHOOL; guest: Ann.";
        Set<String> watchlist = Set.of("acmé school", "ann");

        List<StructuredPiiValidator.Match> matches = validate(text, "SG", watchlist);

        assertSpan(text, "ＡCMÉ\u00a0SCHOOL", FindingCategory.WATCHLIST_MATCH, matches);
        assertSpan(text, "Ann", FindingCategory.WATCHLIST_MATCH, matches);
        assertNoCategory(validate("Joann visited Acmé Schoolhouse", "SG", watchlist),
                FindingCategory.WATCHLIST_MATCH);
    }

    @Test
    public void absentWatchlistNeverClaimsAmbiguousNamesOrOrganizations() {
        String ambiguous = "Ann works at Acmé School in Example Street";

        assertNoCategory(validate(ambiguous, "SG", Set.of()),
                FindingCategory.WATCHLIST_MATCH);
        assertNoCategory(validate(ambiguous, "SG", Set.of("different employer")),
                FindingCategory.WATCHLIST_MATCH);
    }

    @Test
    public void watchlistInputRemainsCallerOwnedAndUnmodified() {
        Set<String> watchlist = new HashSet<>(Set.of("fictional employer"));

        validate("Fictional Employer", "SG", watchlist);

        assertEquals(Set.of("fictional employer"), watchlist);
    }

    @Test
    public void matchAndValidatorCannotRetainPayloadOrLoggingObjects() {
        assertEquals(0, StructuredPiiValidator.class.getDeclaredFields().length);
        for (Field field : StructuredPiiValidator.Match.class.getDeclaredFields()) {
            assertTrue(Modifier.isPrivate(field.getModifiers()));
            assertFalse(isPayloadOrLoggingType(field.getType()));
            String name = field.getName().toLowerCase(Locale.ROOT);
            assertFalse(name.contains("text"));
            assertFalse(name.contains("payload"));
            assertFalse(name.contains("secret"));
        }
        StructuredPiiValidator.Match match = new StructuredPiiValidator.Match(
                FindingCategory.AUTO_EMAIL, 2, 8);
        assertFalse(match.toString().contains("example.org"));
    }

    @Test
    public void nullInputsAreRejectedWithoutCreatingRetainedState() {
        assertThrows(NullPointerException.class,
                () -> validator.validate(null, "SG", Set.of()));
        assertThrows(NullPointerException.class,
                () -> validator.validate("text", null, Set.of()));
        assertThrows(NullPointerException.class,
                () -> validator.validate("text", "SG", null));
    }

    @Test
    public void normalizedUnicodeMatchMapsBackToOriginalUtf16Span() {
        String text = "Cafe\u0301 badge for ＵＳＥＲ@example.org";
        List<StructuredPiiValidator.Match> matches =
                validate(text, "SG", Set.of("café"));

        assertSpan(text, "Cafe\u0301", FindingCategory.WATCHLIST_MATCH, matches);
        assertSpan(text, "ＵＳＥＲ@example.org", FindingCategory.AUTO_EMAIL, matches);
    }

    @Test
    public void boundedInputsAndWatchlistsRejectUnsafeWork() {
        assertThrows(IllegalArgumentException.class,
                () -> validate("x".repeat(16_385), "SG", Set.of()));
        Set<String> tooManyTerms = new HashSet<>();
        for (int index = 0; index < 33; index++) {
            tooManyTerms.add("term " + index);
        }
        assertThrows(IllegalArgumentException.class,
                () -> validate("text", "SG", tooManyTerms));
        assertThrows(IllegalArgumentException.class,
                () -> validate("text", "SG", Set.of("x".repeat(65))));
        assertThrows(IllegalArgumentException.class,
                () -> validate("text", "XX", Set.of()));
    }

    @Test
    public void returnedMatchesAreImmutableAndRejectUnsafeMetadata() {
        List<StructuredPiiValidator.Match> matches =
                validate("Email creator@example.org", "SG", Set.of());

        assertThrows(UnsupportedOperationException.class, matches::clear);
        assertThrows(IllegalArgumentException.class,
                () -> new StructuredPiiValidator.Match(
                        FindingCategory.FACE, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new StructuredPiiValidator.Match(
                        FindingCategory.AUTO_EMAIL, 2, 2));
    }

    @Test
    public void repetitiveLuhnNumbersAndLooseOtpContextRemainHarmlessControls() {
        assertNoCategory(validate(
                        "Card 0000 0000 0000 0000", "SG", Set.of()),
                FindingCategory.AUTO_CARD);
        assertNoCategory(validate(
                        "Verification completed. Inventory 482913", "SG", Set.of()),
                FindingCategory.AUTO_OTP);
    }

    private List<StructuredPiiValidator.Match> validate(
            String text, String region, Set<String> watchlist) {
        return validator.validate(text, region, watchlist);
    }

    private static void assertSpan(
            String source,
            String expectedSpan,
            FindingCategory category,
            List<StructuredPiiValidator.Match> matches) {
        assertTrue(matches.stream()
                .filter(match -> match.category() == category)
                .anyMatch(match -> expectedSpan.equals(
                        source.substring(match.startOffset(), match.endOffset()))));
    }

    private static void assertNoCategory(
            List<StructuredPiiValidator.Match> matches, FindingCategory category) {
        assertTrue(matches.stream().noneMatch(match -> match.category() == category));
    }

    private static boolean isPayloadOrLoggingType(Class<?> type) {
        return type == String.class
                || type == char[].class
                || type == byte[].class
                || type == ByteBuffer.class
                || type == URI.class
                || type.getName().startsWith("java.util.logging")
                || type.getName().startsWith("org.slf4j")
                || type.getName().startsWith("android.util.Log");
    }
}
