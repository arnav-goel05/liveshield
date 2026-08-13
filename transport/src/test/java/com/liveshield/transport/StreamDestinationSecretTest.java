package com.liveshield.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.transport.destination.StreamDestination;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Tests-first contract for session-only destination-secret ownership and erasure. */
public final class StreamDestinationSecretTest {
    private static final String SECRET_TEXT = "fixture-key-Z9!not-real";
    private static final URI ENDPOINT = URI.create("rtmps://live.example.invalid/app");

    @Test
    public void masksSecretWithoutRevealingValueOrLength() {
        try (StreamDestination destination = destination(SECRET_TEXT.toCharArray())) {
            String masked = destination.maskedSecret();

            assertFalse(masked.contains(SECRET_TEXT));
            assertNotEquals(SECRET_TEXT.length(), masked.length());
            assertEquals("••••••••", masked);
        }
    }

    @Test
    public void ownsDefensiveCopiesOfMutableCharacterAndUtf8Inputs() {
        char[] characters = SECRET_TEXT.toCharArray();
        StreamDestination fromCharacters = destination(characters);
        Arrays.fill(characters, 'x');

        byte[] utf8 = SECRET_TEXT.getBytes(StandardCharsets.UTF_8);
        StreamDestination fromBytes = StreamDestination.sessionScopedUtf8(
                StreamDestination.Kind.TIKTOK_EXTERNAL,
                "Controlled external destination",
                ENDPOINT,
                utf8);
        Arrays.fill(utf8, (byte) 'x');

        assertEquals(SECRET_TEXT, copyForAssertion(fromCharacters));
        assertEquals(SECRET_TEXT, copyForAssertion(fromBytes));
        fromCharacters.close();
        fromBytes.close();
    }

    @Test
    public void lendsOnlyAZeroizedTemporaryCopy() {
        try (StreamDestination destination = destination(SECRET_TEXT.toCharArray())) {
            char[][] escapedReference = new char[1][];
            String observed = destination.useSecret(secret -> {
                escapedReference[0] = secret;
                secret[0] = 'X';
                return new String(secret);
            });

            assertTrue(observed.startsWith("X"));
            assertAllZero(escapedReference[0]);
            assertEquals(SECRET_TEXT, copyForAssertion(destination));
        }
    }

    @Test
    public void closeZeroizesOwnedMemoryAndBlocksLaterAccess() throws Exception {
        StreamDestination destination = destination(SECRET_TEXT.toCharArray());
        destination.close();

        assertEquals(StreamDestination.State.CLEARED, destination.state());
        assertAllZero(ownedSecret(destination));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> destination.useSecret(secret -> null));
        assertFalse(failure.getMessage().contains(SECRET_TEXT));
        destination.close();
        assertAllZero(ownedSecret(destination));
    }

    @Test
    public void toStringConsoleAndCallbackErrorsNeverLeakSecret() throws Exception {
        StreamDestination destination = destination(SECRET_TEXT.toCharArray());
        PrintStream originalOut = System.out;
        PrintStream originalError = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        RuntimeException failure;
        try (PrintStream sink = new PrintStream(
                captured, true, StandardCharsets.UTF_8.name())) {
            System.setOut(sink);
            System.setErr(sink);
            System.out.println(destination);
            failure = assertThrows(RuntimeException.class, () -> destination.useSecret(secret -> {
                throw new IllegalArgumentException("Rejected " + new String(secret));
            }));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalError);
            destination.close();
        }

        assertNoSecret(destination.toString());
        assertNoSecret(captured.toString(StandardCharsets.UTF_8.name()));
        assertNoSecret(failure.getClass().getName() + ':' + failure.getMessage());
        assertTrue("Secret-bearing cause must not escape", failure.getCause() == null);
    }

    @Test
    public void publicApiHasNoStringSecretOrPersistenceSurface() {
        assertFalse(Serializable.class.isAssignableFrom(StreamDestination.class));
        assertFalse("Parcelable persistence is forbidden",
                implementsInterfaceNamed(StreamDestination.class, "android.os.Parcelable"));
        for (Constructor<?> constructor : StreamDestination.class.getConstructors()) {
            assertFalse(Arrays.asList(constructor.getParameterTypes()).contains(String.class));
        }
        for (Method method : StreamDestination.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            String normalizedName = method.getName().toLowerCase(java.util.Locale.ROOT);
            if (normalizedName.contains("secret")) {
                assertFalse("Secret API must not accept String: " + method,
                        Arrays.asList(method.getParameterTypes()).contains(String.class));
                assertFalse("Secret API must not return String: " + method,
                        method.getReturnType() == String.class
                                && !normalizedName.equals("maskedsecret"));
            }
            assertFalse("No persistence/save/serialize API: " + method,
                    normalizedName.contains("persist")
                            || normalizedName.contains("save")
                            || normalizedName.contains("serial"));
        }
        for (Field field : StreamDestination.class.getDeclaredFields()) {
            assertFalse("Secret must not be retained as immutable String: " + field,
                    field.getName().toLowerCase(java.util.Locale.ROOT).contains("secret")
                            && field.getType() == String.class);
        }
    }

    @Test
    public void rejectsEndpointEmbeddedCredentialsAndMalformedUtf8WithoutEchoingInput() {
        String fictionalUser = "fixture-user";
        String fictionalPassword = "fixture-password";
        assertRedactedConstructionFailure(
                URI.create("rtmps://" + fictionalUser + ':' + fictionalPassword
                        + "@example.invalid/app"));
        assertRedactedConstructionFailure(
                URI.create("rtmps://example.invalid/app?key=fixture-query-secret"));
        assertRedactedConstructionFailure(
                URI.create("https://example.invalid/app"));

        byte[] malformed = {(byte) 0xc3, (byte) 0x28};
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> StreamDestination.sessionScopedUtf8(
                        StreamDestination.Kind.TIKTOK_EXTERNAL,
                        "Controlled external destination",
                        ENDPOINT,
                        malformed));
        assertFalse(failure.getMessage().contains(Arrays.toString(malformed)));
    }

    @Test
    public void acceptsDnsIpv4AndBracketedIpv6ButRejectsMalformedAuthorities() {
        for (String endpoint : new String[]{
                "rtmps://live.example.invalid/app",
                "rtmp://10.0.2.2:1935/liveshield",
                "rtmps://[2001:db8::1]:1935/app"}) {
            try (StreamDestination destination = StreamDestination.sessionScoped(
                    StreamDestination.Kind.TIKTOK_EXTERNAL,
                    "Controlled destination",
                    URI.create(endpoint),
                    SECRET_TEXT.toCharArray())) {
                assertEquals(StreamDestination.State.VALIDATED, destination.state());
            }
        }
        for (String endpoint : new String[]{
                "rtmp://256.0.2.2:1935/app",
                "rtmp://10.0.2.2:70000/app",
                "rtmps://[2001:db8::zz]:1935/app",
                "rtmps://bad_host/app"}) {
            assertThrows(IllegalArgumentException.class, () -> StreamDestination.sessionScoped(
                    StreamDestination.Kind.TIKTOK_EXTERNAL,
                    "Controlled destination",
                    URI.create(endpoint),
                    SECRET_TEXT.toCharArray()));
        }
    }

    @Test
    public void callbackFailureStillZeroizesTemporaryAndUsesRedactedException() {
        try (StreamDestination destination = destination(SECRET_TEXT.toCharArray())) {
            char[][] escapedReference = new char[1][];
            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> destination.useSecret(secret -> {
                        escapedReference[0] = secret;
                        throw new IllegalStateException("Remote echoed " + new String(secret));
                    }));

            assertAllZero(escapedReference[0]);
            assertNoSecret(failure.getMessage());
            assertTrue(failure.getCause() == null);
            assertEquals(SECRET_TEXT, copyForAssertion(destination));
        }
    }

    @Test
    public void concurrentCloseWaitsForLoanThenClearsAndBlocksAllLaterLoans() throws Exception {
        StreamDestination destination = destination(SECRET_TEXT.toCharArray());
        CountDownLatch loanStarted = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseLoan = new CountDownLatch(1);
        AtomicReference<char[]> loaned = new AtomicReference<>();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread borrower = new Thread(() -> {
            try {
                destination.useSecret(secret -> {
                    loaned.set(secret);
                    loanStarted.countDown();
                    await(releaseLoan);
                    return null;
                });
            } catch (Throwable failure) {
                workerFailure.set(failure);
            }
        }, "secret-borrower");
        Thread closer = new Thread(() -> {
            closeStarted.countDown();
            destination.close();
        }, "secret-closer");

        borrower.start();
        assertTrue(loanStarted.await(2, TimeUnit.SECONDS));
        closer.start();
        assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
        releaseLoan.countDown();
        borrower.join(2_000L);
        closer.join(2_000L);

        assertFalse(borrower.isAlive());
        assertFalse(closer.isAlive());
        assertTrue(workerFailure.get() == null);
        assertAllZero(loaned.get());
        assertEquals(StreamDestination.State.CLEARED, destination.state());
        assertThrows(IllegalStateException.class,
                () -> destination.useSecret(secret -> null));
    }

    private static StreamDestination destination(char[] secret) {
        return StreamDestination.sessionScoped(
                StreamDestination.Kind.TIKTOK_EXTERNAL,
                "Controlled external destination",
                ENDPOINT,
                secret);
    }

    private static String copyForAssertion(StreamDestination destination) {
        return destination.useSecret(String::new);
    }

    private static char[] ownedSecret(StreamDestination destination) throws Exception {
        Field field = StreamDestination.class.getDeclaredField("secret");
        field.setAccessible(true);
        return (char[]) field.get(destination);
    }

    private static void assertAllZero(char[] characters) {
        assertTrue("Secret buffer was not zeroized: " + Arrays.toString(characters),
                characters != null && characters.length > 0);
        for (char character : characters) {
            assertEquals('\0', character);
        }
    }

    private static void assertNoSecret(String value) {
        assertFalse("Secret leaked through observable text: " + value,
                value != null && value.contains(SECRET_TEXT));
    }

    private static void assertRedactedConstructionFailure(URI endpoint) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> StreamDestination.sessionScoped(
                        StreamDestination.Kind.TIKTOK_EXTERNAL,
                        "Controlled external destination",
                        endpoint,
                        SECRET_TEXT.toCharArray()));
        assertNoSecret(failure.getMessage());
        assertFalse(failure.getMessage().contains("fixture-password"));
        assertFalse(failure.getMessage().contains("fixture-query-secret"));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for secret-lifecycle test handoff");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Secret-lifecycle test interrupted", exception);
        }
    }

    private static boolean implementsInterfaceNamed(Class<?> type, String interfaceName) {
        for (Class<?> implemented : type.getInterfaces()) {
            if (implemented.getName().equals(interfaceName)) {
                return true;
            }
        }
        return false;
    }
}
