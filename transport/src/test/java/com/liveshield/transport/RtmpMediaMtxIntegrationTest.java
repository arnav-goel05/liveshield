package com.liveshield.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.SuppressLint;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.Assume;
import org.junit.Test;

/** Opt-in local integration contract for the video-only RTMP publisher. */
public final class RtmpMediaMtxIntegrationTest {
    private static final String OPT_IN_PROPERTY = "liveshield.rtmp.integration";
    private static final String BINARY_PROPERTY = "liveshield.mediamtx.binary";
    private static final String EXPECTED_BINARY_VERSION = "v1.15.5";
    private static final String EXPECTED_BINARY_SHA256 =
            "77e8f24ce5fea5f0b8e69727cc5f5ded5cd09645096ec8c28532ae96c6be6e4a";
    private static final String ENDPOINT = "rtmp://127.0.0.1:1935/liveshield";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(20);
    private static final int WIDTH = 160;
    private static final int HEIGHT = 90;
    private static final int FPS = 5;
    // This is one continuous Base64 fixture; whitespace would change the decoded H.264 bytes.
    @SuppressLint("TextConcatSpace")
    private static final String SYNTHETIC_H264 =
            "AAAAAWdCwB7aCjfkwEQAAAMABAAAAwAqPFi6gAAAAAFozg/IAAABBgX//03cRem95tlIt5Ys"
            + "2CDZI+7veDI2NCAtIGNvcmUgMTY1IHIzMjIyIGIzNTYwNWEgLSBILjI2NC9NUEVHLTQgQV"
            + "ZDIGNvZGVjIC0gQ29weWxlZnQgMjAwMy0yMDI1IC0gaHR0cDovL3d3dy52aWRlb2xhbi5v"
            + "cmcveDI2NC5odG1sIC0gb3B0aW9uczogY2FiYWM9MCByZWY9MSBkZWJsb2NrPTA6MDowIG"
            + "FuYWx5c2U9MDowIG1lPWRpYSBzdWJtZT0wIHBzeT0xIHBzeV9yZD0xLjAwOjAuMDAgbWl4"
            + "ZWRfcmVmPTAgbWVfcmFuZ2U9MTYgY2hyb21hX21lPTEgdHJlbGxpcz0wIDh4OGRjdD0wIG"
            + "NxbT0wIGRlYWR6b25lPTIxLDExIGZhc3RfcHNraXA9MSBjaHJvbWFfcXBfb2Zmc2V0PTAg"
            + "dGhyZWFkcz0xIGxvb2thaGVhZF90aHJlYWRzPTEgc2xpY2VkX3RocmVhZHM9MCBucj0wIG"
            + "RlY2ltYXRlPTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9p"
            + "bnRyYT0wIGJmcmFtZXM9MCB3ZWlnaHRwPTAga2V5aW50PTUga2V5aW50X21pbj0zIHNjZW"
            + "5lY3V0PTAgaW50cmFfcmVmcmVzaD0wIHJjPWNyZiBtYnRyZWU9MCBjcmY9MjMuMCBxY29t"
            + "cD0wLjYwIHFwbWluPTAgcXBtYXg9NjkgcXBzdGVwPTQgaXBfcmF0aW89MS40MCBhcT0wAI"
            + "AAA AFliIQ6JigACQLJycnJycnJycnXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            + "XXXXXXXXXXXXgAAAAFBmiARoHsAAAABQZpAEqB7AAAAAUGaYBKgewAAAAFBmoASoHs=";

    @Test
    public void relayAndVideoOnlyProbeContractsRemainPinned() throws Exception {
        File workspace = workspaceRoot();
        File compose = new File(workspace, "dev/mediamtx/compose.yml");
        File config = new File(workspace, "dev/mediamtx/mediamtx.yml");
        assertTrue("Pinned MediaMTX compose file is missing", compose.isFile());
        assertTrue("Pinned MediaMTX config is missing", config.isFile());
        assertTrue(readFile(compose).contains(
                "bluenviron/mediamtx:1.15.5@sha256:"
                        + "59aaad04627c7c8f40ceb01a5ff1c43f91e01939da147c3419f1aaa0c78d6cf5"));
        String relayConfig = readFile(config);
        assertTrue(relayConfig.contains("rtmp: yes"));
        assertTrue(relayConfig.contains("record: no"));
        assertFalse(relayConfig.contains("action: playback"));
        assertProbeObservedVideoOnly(
                "streams.stream.0.index=0\n"
                        + "streams.stream.0.codec_name=\"h264\"\n"
                        + "streams.stream.0.codec_type=\"video\"\n"
                        + "packets.packet.0.stream_index=0\n");
    }

    @Test
    public void publishesOneH264VideoTrackAndNoAudioPacketsToPinnedMediaMtx()
            throws Exception {
        Assume.assumeTrue(
                "Set -D" + OPT_IN_PROPERTY + "=true to run the RTMP integration test",
                Boolean.getBoolean(OPT_IN_PROPERTY));
        Assume.assumeTrue(
                "The production RootEncoder publisher requires Android. Run "
                        + "tools/mediamtx/run-api36-rtmp-integration.sh instead.",
                false);
        File workspace = workspaceRoot();
        MediaMtxServer server = null;
        Process probe = null;
        try {
            server = MediaMtxServer.start(workspace, System.getProperty(BINARY_PROPERTY));
            awaitPort(1935, STARTUP_TIMEOUT);
            probe = startProbe();
            try (PublisherFacade publisher = PublisherFacade.create()) {
                publisher.connect(ENDPOINT);
                awaitPublishing(publisher, PUBLISH_TIMEOUT);
                publishSyntheticVideo(publisher);
            }

            String probeOutput = finish(probe, PROCESS_TIMEOUT);
            probe = null;
            assertProbeObservedVideoOnly(probeOutput);
        } finally {
            if (probe != null) {
                stopProcess(probe, Duration.ofSeconds(5));
            }
            if (server != null) {
                server.close();
            }
        }
    }

    private static void publishSyntheticVideo(PublisherFacade publisher) throws Exception {
        List<byte[]> nals = splitAnnexB(Base64.getDecoder().decode(
                SYNTHETIC_H264.replace(" ", "")));
        byte[] configuration = concatenate(nals.stream()
                .filter(nal -> nalType(nal) == 7 || nalType(nal) == 8)
                .toList());
        List<byte[]> pictures = nals.stream()
                .filter(nal -> nalType(nal) == 5 || nalType(nal) == 1)
                .toList();
        assertFalse("Synthetic fixture must contain SPS/PPS", configuration.length == 0);
        assertEquals("Synthetic fixture must contain one five-frame GOP", 5, pictures.size());

        publisher.publish(EncodedAccessUnit.copySanitizedH264(
                configuration, 0, Set.of(EncodedAccessUnit.Flag.CODEC_CONFIGURATION)));
        long presentationTimeUs = 0;
        for (int repetition = 0; repetition < 4; repetition++) {
            for (byte[] picture : pictures) {
                Set<EncodedAccessUnit.Flag> flags = nalType(picture) == 5
                        ? Set.of(EncodedAccessUnit.Flag.KEY_FRAME) : Set.of();
                publisher.publish(EncodedAccessUnit.copySanitizedH264(
                        picture, presentationTimeUs, flags));
                presentationTimeUs += 1_000_000L / FPS;
                Thread.sleep(1_000L / FPS);
            }
        }
    }

    private static Process startProbe() throws IOException {
        return new ProcessBuilder(
                "ffprobe", "-v", "error",
                "-read_intervals", "%+3",
                "-show_entries", "stream=index,codec_type,codec_name:packet=stream_index",
                "-of", "flat", ENDPOINT)
                .redirectErrorStream(true)
                .start();
    }

    private static void assertProbeObservedVideoOnly(String output) {
        List<String> lines = lines(output);
        long streamIndexes = lines.stream()
                .filter(line -> line.matches("streams\\.stream\\.[0-9]+\\.index=.*"))
                .count();
        long packetIndexes = lines.stream()
                .filter(line -> line.matches("packets\\.packet\\.[0-9]+\\.stream_index=.*"))
                .count();
        assertEquals("Expected exactly one published media track\n" + output, 1, streamIndexes);
        assertTrue("Expected H.264 video track\n" + output,
                output.contains("codec_name=\"h264\"")
                        && output.contains("codec_type=\"video\""));
        assertFalse("Audio track or packet appeared\n" + output,
                output.toLowerCase(Locale.ROOT).contains("audio"));
        assertTrue("Expected at least one video packet\n" + output, packetIndexes > 0);
        assertTrue("Every packet must belong to the only video stream\n" + output,
                lines.stream()
                        .filter(line -> line.contains("stream_index="))
                        .allMatch(line -> line.endsWith("=0")));
    }

    private static List<String> composeCommand(File compose, String... arguments) {
        List<String> command = new ArrayList<>(List.of(
                "docker", "compose", "-f", compose.getAbsolutePath()));
        command.addAll(List.of(arguments));
        return command;
    }

    private static final class MediaMtxServer implements AutoCloseable {
        private final File compose;
        private final Process binaryProcess;

        private MediaMtxServer(File compose, Process binaryProcess) {
            this.compose = compose;
            this.binaryProcess = binaryProcess;
        }

        static MediaMtxServer start(File workspace, String requestedBinary) throws Exception {
            File config = new File(workspace, "dev/mediamtx/mediamtx.yml");
            assertTrue("Pinned MediaMTX config is missing", config.isFile());
            if (requestedBinary != null && !requestedBinary.isBlank()) {
                File binary = new File(requestedBinary).getCanonicalFile();
                assertTrue("Explicit MediaMTX binary is not a regular file", binary.isFile());
                assertTrue("Explicit MediaMTX binary is not executable", binary.canExecute());
                assertEquals("Explicit MediaMTX binary SHA-256 mismatch",
                        EXPECTED_BINARY_SHA256, sha256(binary));
                String version = runBounded(PROCESS_TIMEOUT,
                        List.of(binary.getAbsolutePath(), "--version")).trim();
                assertEquals("Explicit MediaMTX binary version mismatch",
                        EXPECTED_BINARY_VERSION, version);
                Process process = new ProcessBuilder(
                        binary.getAbsolutePath(), config.getAbsolutePath())
                        .redirectErrorStream(true)
                        .start();
                drainAsync(process.getInputStream());
                return new MediaMtxServer(null, process);
            }

            File compose = new File(workspace, "dev/mediamtx/compose.yml");
            assertTrue("Pinned MediaMTX compose file is missing", compose.isFile());
            assertTrue(readFile(compose).contains(
                    "bluenviron/mediamtx:1.15.5@sha256:"
                            + "59aaad04627c7c8f40ceb01a5ff1c43f91e01939da147c3419f1aaa0c78d6cf5"));
            try {
                runBounded(PROCESS_TIMEOUT, composeCommand(compose, "up", "-d", "--wait",
                        "--wait-timeout", Long.toString(STARTUP_TIMEOUT.toSeconds())));
                return new MediaMtxServer(compose, null);
            } catch (Exception startupFailure) {
                runBounded(PROCESS_TIMEOUT,
                        composeCommand(compose, "down", "--timeout", "5", "--remove-orphans"));
                throw startupFailure;
            }
        }

        @Override
        public void close() throws Exception {
            if (binaryProcess != null) {
                stopProcess(binaryProcess, Duration.ofSeconds(5));
            } else {
                runBounded(PROCESS_TIMEOUT,
                        composeCommand(compose, "down", "--timeout", "5", "--remove-orphans"));
            }
        }
    }

    private static String runBounded(Duration timeout, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        return finish(process, timeout);
    }

    private static void drainAsync(InputStream input) {
        Thread drainer = new Thread(() -> {
            byte[] buffer = new byte[4096];
            try (InputStream stream = input) {
                while (stream.read(buffer) >= 0) {
                    // MediaMTX logs contain no test evidence; consume only to prevent backpressure.
                }
            } catch (IOException ignoredAfterShutdown) {
                // The process closing its pipe is normal during bounded cleanup.
            }
        }, "mediamtx-test-log-drainer");
        drainer.setDaemon(true);
        drainer.start();
    }

    private static String finish(Process process, Duration timeout) throws Exception {
        if (!waitForExit(process, timeout)) {
            stopProcess(process, Duration.ofSeconds(5));
            fail("Process exceeded " + timeout.toSeconds() + " second timeout");
        }
        String output = new String(readAllBytes(process.getInputStream()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("Process failed:\n" + output, 0, process.exitValue());
        return output;
    }

    private static boolean waitForExit(Process process, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException stillRunning) {
                Thread.sleep(25);
            }
        }
        return false;
    }

    private static void stopProcess(Process process, Duration timeout)
            throws InterruptedException {
        process.destroy();
        if (!waitForExit(process, timeout)) {
            fail("Process did not stop within " + timeout.toSeconds() + " seconds");
        }
    }

    private static List<String> lines(String value) {
        return List.of(value.split("\\R"));
    }

    private static void awaitPort(int port, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
                return;
            } catch (IOException unavailable) {
                Thread.sleep(100);
            }
        }
        fail("MediaMTX RTMP port did not become ready within " + timeout.toSeconds() + " seconds");
    }

    private static void awaitPublishing(PublisherFacade publisher, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (publisher.isPublishing()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Publisher did not reach publishing state within " + timeout.toSeconds() + " seconds");
    }

    private static File workspaceRoot() {
        File current = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (current != null) {
            if (new File(current, "dev/mediamtx/compose.yml").isFile()) {
                return current;
            }
            current = current.getParentFile();
        }
        throw new IllegalStateException("Could not locate workspace root");
    }

    private static String readFile(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return new String(readAllBytes(input), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        char[] hex = new char[digest.getDigestLength() * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        byte[] bytes = digest.digest();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            hex[index * 2] = alphabet[value >>> 4];
            hex[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(hex);
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static List<byte[]> splitAnnexB(byte[] stream) {
        List<byte[]> units = new ArrayList<>();
        int start = findStartCode(stream, 0);
        while (start >= 0) {
            int next = findStartCode(stream, start + startCodeLength(stream, start));
            int end = next < 0 ? stream.length : next;
            units.add(java.util.Arrays.copyOfRange(stream, start, end));
            start = next;
        }
        return List.copyOf(units);
    }

    private static int findStartCode(byte[] bytes, int from) {
        for (int index = from; index + 3 < bytes.length; index++) {
            if (bytes[index] == 0 && bytes[index + 1] == 0
                    && (bytes[index + 2] == 1
                    || (bytes[index + 2] == 0 && bytes[index + 3] == 1))) {
                return index;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] bytes, int offset) {
        return bytes[offset + 2] == 1 ? 3 : 4;
    }

    private static int nalType(byte[] annexB) {
        return annexB[startCodeLength(annexB, 0)] & 0x1f;
    }

    private static byte[] concatenate(List<byte[]> arrays) {
        int length = arrays.stream().mapToInt(array -> array.length).sum();
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    /**
     * Reflection keeps the tests-first source compilable until T079 creates the production type.
     */
    private static final class PublisherFacade implements AutoCloseable {
        private static final String CLASS_NAME =
                "com.liveshield.transport.rtmp.RtmpStreamPublisher";
        private final Object publisher;
        private final Method connect;
        private final Method isPublishing;
        private final Method publish;
        private final Method close;

        private PublisherFacade(Object publisher, Class<?> type) throws NoSuchMethodException {
            this.publisher = publisher;
            connect = type.getMethod("connect", String.class);
            isPublishing = type.getMethod("isPublishing");
            publish = type.getMethod("publish", EncodedAccessUnit.class);
            close = type.getMethod("close");
            for (Method method : type.getMethods()) {
                assertFalse("Publisher must expose no audio API: " + method,
                        method.getName().toLowerCase(Locale.ROOT).contains("audio"));
            }
        }

        static PublisherFacade create() throws Exception {
            Class<?> type;
            try {
                type = Class.forName(CLASS_NAME);
            } catch (ClassNotFoundException missingT079) {
                throw new AssertionError("T079 RTMP publisher is not implemented", missingT079);
            }
            Object instance = type.getConstructor(int.class, int.class, int.class)
                    .newInstance(WIDTH, HEIGHT, FPS);
            return new PublisherFacade(instance, type);
        }

        void connect(String endpoint) throws Exception {
            invoke(connect, endpoint);
        }

        boolean isPublishing() throws Exception {
            return (boolean) invoke(isPublishing);
        }

        void publish(EncodedAccessUnit unit) throws Exception {
            invoke(publish, unit);
        }

        @Override
        public void close() throws Exception {
            invoke(close);
        }

        private Object invoke(Method method, Object... arguments) throws Exception {
            try {
                return method.invoke(publisher, arguments);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(cause);
            }
        }
    }
}
