package com.liveshield.app.setup;

import com.liveshield.transport.destination.StreamDestination;
import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

/** Converts private setup controls into a session-only destination without String secret input. */
final class DestinationForm {
    static final URI LOCAL_DEMO_ENDPOINT = URI.create("rtmp://10.0.2.2:1935/liveshield");

    private DestinationForm() {
    }

    static StreamDestination localDemo() {
        return StreamDestination.sessionScoped(
                StreamDestination.Kind.LOCAL_DEMO,
                "Controlled MediaMTX demonstration",
                LOCAL_DEMO_ENDPOINT,
                new char[0]);
    }

    static StreamDestination external(String endpoint, CharSequence secretInput) {
        if (secretInput == null || secretInput.length() == 0) {
            throw new ValidationException(ValidationError.SECRET_REQUIRED);
        }
        char[] secret = new char[secretInput.length()];
        for (int index = 0; index < secret.length; index++) {
            secret[index] = secretInput.charAt(index);
        }
        try {
            try {
                return StreamDestination.sessionScoped(
                        StreamDestination.Kind.TIKTOK_EXTERNAL,
                        "TikTok external broadcast",
                        URI.create(Objects.requireNonNull(endpoint, "endpoint").trim()),
                        secret);
            } catch (IllegalArgumentException | NullPointerException failure) {
                throw new ValidationException(ValidationError.ENDPOINT_INVALID);
            }
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    enum ValidationError {
        ENDPOINT_INVALID,
        SECRET_REQUIRED
    }

    static final class ValidationException extends RuntimeException {
        private final ValidationError error;

        private ValidationException(ValidationError error) {
            super("Stream destination form is invalid");
            this.error = error;
        }

        ValidationError error() {
            return error;
        }
    }
}
