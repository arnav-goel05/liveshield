package com.liveshield.transport.destination;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Session-scoped, non-persistable destination and explicitly erasable secret owner. */
public final class StreamDestination implements AutoCloseable {
    private static final String MASK = "••••••••";
    private static final String CLEARED_MESSAGE = "Destination secret is unavailable";
    private static final String OPERATION_MESSAGE = "Destination secret operation failed";

    private final Kind kind;
    private final String displayLabel;
    private final URI endpoint;
    private final char[] secret;
    private State state = State.VALIDATED;

    private StreamDestination(Kind kind, String displayLabel, URI endpoint, char[] secret) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.displayLabel = validateDisplayLabel(displayLabel);
        this.endpoint = validateEndpoint(endpoint);
        char[] supplied = Objects.requireNonNull(secret, "secret");
        if (kind == Kind.TIKTOK_EXTERNAL && supplied.length == 0) {
            throw new IllegalArgumentException("Destination secret must not be empty");
        }
        this.secret = Arrays.copyOf(supplied, supplied.length);
    }

    public static StreamDestination sessionScoped(
            Kind kind,
            String displayLabel,
            URI endpoint,
            char[] secret) {
        return new StreamDestination(kind, displayLabel, endpoint, secret);
    }

    public static StreamDestination sessionScopedUtf8(
            Kind kind,
            String displayLabel,
            URI endpoint,
            byte[] secret) {
        char[] decoded = decodeUtf8(Objects.requireNonNull(secret, "secret"));
        try {
            return new StreamDestination(kind, displayLabel, endpoint, decoded);
        } finally {
            Arrays.fill(decoded, '\0');
        }
    }

    public String maskedSecret() {
        return MASK;
    }

    public synchronized <T> T useSecret(SecretOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        ensureAvailable();
        char[] temporary = Arrays.copyOf(secret, secret.length);
        try {
            return operation.apply(temporary);
        } catch (RuntimeException failure) {
            throw new SecretOperationException(OPERATION_MESSAGE);
        } finally {
            Arrays.fill(temporary, '\0');
        }
    }

    public synchronized State state() {
        return state;
    }

    /** Returns the validated non-secret endpoint; credentials are never embedded in this URI. */
    public URI endpoint() {
        return endpoint;
    }

    @Override
    public synchronized void close() {
        if (state != State.CLEARED) {
            Arrays.fill(secret, '\0');
            state = State.CLEARED;
        }
    }

    @Override
    public synchronized String toString() {
        return "StreamDestination{kind=" + kind
                + ", displayLabel=" + displayLabel
                + ", endpoint=" + endpoint
                + ", secret=" + MASK
                + ", state=" + state + '}';
    }

    private void ensureAvailable() {
        if (state == State.CLEARED) {
            throw new IllegalStateException(CLEARED_MESSAGE);
        }
    }

    private static String validateDisplayLabel(String value) {
        String label = Objects.requireNonNull(value, "displayLabel").trim();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("Destination display label must not be empty");
        }
        for (int index = 0; index < label.length(); index++) {
            if (Character.isISOControl(label.charAt(index))) {
                throw new IllegalArgumentException(
                        "Destination display label contains control data");
            }
        }
        return label;
    }

    private static URI validateEndpoint(URI value) {
        URI candidate = Objects.requireNonNull(value, "endpoint");
        String scheme = candidate.getScheme();
        String authority = candidate.getRawAuthority();
        if (scheme == null
                || !(scheme.toLowerCase(Locale.ROOT).equals("rtmp")
                || scheme.toLowerCase(Locale.ROOT).equals("rtmps"))
                || authority == null
                || !hasValidAuthority(authority)) {
            throw new IllegalArgumentException("Destination endpoint must be an absolute RTMP URI");
        }
        if (candidate.getRawUserInfo() != null
                || candidate.getRawQuery() != null
                || candidate.getRawFragment() != null) {
            throw new IllegalArgumentException("Destination endpoint must not embed credentials");
        }
        return candidate.normalize();
    }

    private static boolean hasValidAuthority(String authority) {
        if (authority.isEmpty() || authority.indexOf('@') >= 0) {
            return false;
        }
        String host;
        String port = null;
        if (authority.charAt(0) == '[') {
            int close = authority.indexOf(']');
            if (close < 0) {
                return false;
            }
            host = authority.substring(1, close);
            if (close + 1 < authority.length()) {
                if (authority.charAt(close + 1) != ':') {
                    return false;
                }
                port = authority.substring(close + 2);
            }
            if (!isValidIpv6Literal(host)) {
                return false;
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                if (authority.indexOf(':') != colon) {
                    return false;
                }
                host = authority.substring(0, colon);
                port = authority.substring(colon + 1);
            } else {
                host = authority;
            }
            if (!isValidIpv4Literal(host)
                    && (looksLikeIpv4Literal(host) || !isValidDnsName(host))) {
                return false;
            }
        }
        return port == null || isValidPort(port);
    }

    private static boolean isValidDnsName(String host) {
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty()
                    || label.length() > 63
                    || label.charAt(0) == '-'
                    || label.charAt(label.length() - 1) == '-') {
                return false;
            }
            for (int index = 0; index < label.length(); index++) {
                char character = label.charAt(index);
                if (!Character.isLetterOrDigit(character) && character != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isValidIpv4Literal(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            int value = 0;
            for (int index = 0; index < octet.length(); index++) {
                char character = octet.charAt(index);
                if (!Character.isDigit(character)) {
                    return false;
                }
                value = value * 10 + character - '0';
            }
            if (value > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeIpv4Literal(String host) {
        if (host.isEmpty()) {
            return false;
        }
        for (int index = 0; index < host.length(); index++) {
            char character = host.charAt(index);
            if (!Character.isDigit(character) && character != '.') {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidIpv6Literal(String host) {
        if (host.isEmpty() || host.indexOf(':') < 0 || host.indexOf('%') >= 0) {
            return false;
        }
        int compression = host.indexOf("::");
        if (compression >= 0 && compression != host.lastIndexOf("::")) {
            return false;
        }
        String[] groups = host.split(":", -1);
        int populated = 0;
        for (String group : groups) {
            if (group.isEmpty()) {
                continue;
            }
            populated++;
            if (group.length() > 4) {
                return false;
            }
            for (int index = 0; index < group.length(); index++) {
                if (Character.digit(group.charAt(index), 16) < 0) {
                    return false;
                }
            }
        }
        return compression >= 0 ? populated < 8 : populated == 8;
    }

    private static boolean isValidPort(String port) {
        if (port.isEmpty() || port.length() > 5) {
            return false;
        }
        int value = 0;
        for (int index = 0; index < port.length(); index++) {
            char character = port.charAt(index);
            if (!Character.isDigit(character)) {
                return false;
            }
            value = value * 10 + character - '0';
        }
        return value > 0 && value <= 65_535;
    }

    private static char[] decodeUtf8(byte[] encoded) {
        byte[] encodedCopy = Arrays.copyOf(encoded, encoded.length);
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encodedCopy));
            char[] characters = new char[decoded.remaining()];
            decoded.get(characters);
            if (decoded.hasArray()) {
                Arrays.fill(decoded.array(), '\0');
            }
            return characters;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Destination secret is not valid UTF-8");
        } finally {
            Arrays.fill(encodedCopy, (byte) 0);
        }
    }

    public enum Kind {
        LOCAL_DEMO,
        TIKTOK_EXTERNAL
    }

    public enum State {
        VALIDATED,
        CLEARED
    }

    @FunctionalInterface
    public interface SecretOperation<T> {
        T apply(char[] secret);
    }

    private static final class SecretOperationException extends RuntimeException {
        private SecretOperationException(String message) {
            super(message);
        }
    }
}
