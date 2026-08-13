package com.liveshield.app.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import com.liveshield.transport.destination.StreamDestination;
import java.util.Arrays;
import org.junit.Test;

/** Pure JVM coverage for the no-String secret handoff into session ownership. */
public final class DestinationFormTest {
    @Test
    public void controlledDemoUsesFixedNonCredentialEndpointAndNoSecret() {
        try (StreamDestination destination = DestinationForm.localDemo()) {
            assertFalse(destination.toString().contains("@"));
            assertFalse(destination.toString().contains("?"));
            assertEquals("", destination.useSecret(String::new));
        }
    }

    @Test
    public void externalCopiesCharactersAndDoesNotMutateInput() {
        char[] mutable = "fictional-key".toCharArray();
        CharSequence input = new CharArraySequence(mutable);
        StreamDestination destination = DestinationForm.external(
                "rtmps://live.example.invalid/app", input);
        Arrays.fill(mutable, 'x');

        assertEquals("fictional-key", destination.useSecret(String::new));
        destination.close();
    }

    @Test
    public void externalRejectsMissingSecretAndCredentialBearingEndpoint() {
        DestinationForm.ValidationException missing = assertThrows(
                DestinationForm.ValidationException.class,
                () -> DestinationForm.external("rtmps://live.example.invalid/app", ""));
        assertEquals(DestinationForm.ValidationError.SECRET_REQUIRED, missing.error());
        String user = "fictional-user";
        String password = "fictional-password";
        DestinationForm.ValidationException invalid = assertThrows(
                DestinationForm.ValidationException.class,
                () -> DestinationForm.external(
                        "rtmps://" + user + ':' + password + "@live.example.invalid/app",
                        "fictional-key"));
        assertEquals(DestinationForm.ValidationError.ENDPOINT_INVALID, invalid.error());
        assertFalse(invalid.getMessage().contains(password));
    }

    private static final class CharArraySequence implements CharSequence {
        private final char[] values;

        private CharArraySequence(char[] values) {
            this.values = values;
        }

        @Override
        public int length() {
            return values.length;
        }

        @Override
        public char charAt(int index) {
            return values[index];
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new CharArraySequence(Arrays.copyOfRange(values, start, end));
        }
    }
}
