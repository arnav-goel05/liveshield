package com.liveshield.vision.pii;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Copies only hash-verified, bundled PaddleOCR assets to private model storage. */
final class PaddleOcrAssets {
    static final Asset DETECTOR = new Asset(
            "models/paddleocr/en_PP-OCRv3_det_slim_infer.nb",
            "en_PP-OCRv3_det_slim_infer.nb",
            925_070L,
            "9d3c629313d47d203385216a756610eb00ee2496a06ff724cc34904deda70f22",
            "v2.10");
    static final Asset RECOGNIZER = new Asset(
            "models/paddleocr/en_PP-OCRv3_rec_slim_infer.nb",
            "en_PP-OCRv3_rec_slim_infer.nb",
            3_313_574L,
            "053b3a99fc88233c5ea5fda10141cf2f9c81e93ca2b74ce3dcf8208d3e80185d",
            "v2.11-rc");
    static final Asset DICTIONARY = new Asset(
            "models/paddleocr/en_dict.txt",
            "en_dict.txt",
            190L,
            "5662df9d2d03f0e8ca0d3b0649d6acbab904b6a14b3d3521463c71c37c668ce3",
            null);
    private static final List<Asset> ASSETS = List.of(DETECTOR, RECOGNIZER, DICTIONARY);

    private PaddleOcrAssets() {
    }

    static File verifiedPrivateCopy(Context context, Asset asset) {
        File directory = new File(context.getNoBackupFilesDir(), "paddleocr-v3");
        if (!directory.exists() && !directory.mkdir()) {
            throw new IllegalStateException("Unable to create private OCR model directory");
        }
        File output = new File(directory, asset.outputName());
        try {
            if (output.isFile() && verified(output, asset)) {
                return output;
            }
            try (InputStream input = context.getAssets().open(asset.assetPath());
                    FileOutputStream sink = new FileOutputStream(output, false)) {
                byte[] buffer = new byte[16_384];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    sink.write(buffer, 0, read);
                }
            }
            if (!verified(output, asset)) {
                throw new IllegalStateException("Bundled OCR model integrity check failed");
            }
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to prepare bundled OCR model", exception);
        }
    }

    static void verifyBundledAssets(Context context) {
        for (Asset asset : ASSETS) {
            verifiedPrivateCopy(context, asset);
        }
    }

    static List<String> verifiedDictionary(Context context) {
        File dictionary = verifiedPrivateCopy(context, DICTIONARY);
        List<String> characters = new java.util.ArrayList<>();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(dictionary), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                characters.add(line);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read bundled OCR dictionary", exception);
        }
        // The audited PP-OCRv3 configuration enables use_space_char. The official dictionary
        // already ends in a space entry, so this deliberately preserves the exported duplicate.
        characters.add(" ");
        if (characters.size() != 96 || characters.stream().anyMatch(String::isEmpty)) {
            throw new IllegalStateException("Unexpected bundled OCR dictionary contract");
        }
        return List.copyOf(characters);
    }

    private static boolean verified(File file, Asset asset) throws IOException {
        if (file.length() != asset.size()) {
            return false;
        }
        try (InputStream input = new java.io.FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            if (!toHex(digest.digest()).equals(asset.sha256())) {
                return false;
            }
            return asset.optimizerVersion() == null
                    || asset.optimizerVersion().equals(readOptimizerVersion(file));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            value.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            value.append(Character.forDigit(item & 0x0f, 16));
        }
        return value.toString();
    }

    private static String readOptimizerVersion(File model) throws IOException {
        byte[] header = new byte[18];
        try (InputStream input = new java.io.FileInputStream(model)) {
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) {
                    throw new IOException("Truncated Paddle model header");
                }
                offset += read;
            }
        }
        int metaVersion = (header[0] & 0xff) | ((header[1] & 0xff) << 8);
        if (metaVersion != 2) {
            throw new IOException("Unsupported Paddle model metadata version");
        }
        int end = 2;
        while (end < header.length && header[end] != 0) {
            end++;
        }
        return new String(header, 2, end - 2, StandardCharsets.US_ASCII);
    }

    static boolean runtimeSupportsOptimizer(String runtime, String optimizer) {
        return "v2.11".equals(runtime)
                && ("v2.10".equals(optimizer) || "v2.11-rc".equals(optimizer));
    }

    record Asset(
            String assetPath,
            String outputName,
            long size,
            String sha256,
            String optimizerVersion) {
    }
}
