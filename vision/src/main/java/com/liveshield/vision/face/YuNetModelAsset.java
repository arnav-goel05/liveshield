package com.liveshield.vision.face;

import android.content.res.AssetManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Loads the pinned offline YuNet model only after verifying its reviewed upstream digest. */
final class YuNetModelAsset {
    static final String ASSET_PATH = "models/face_detection_yunet_2023mar.onnx";
    static final int EXPECTED_BYTES = 232_589;
    static final String EXPECTED_SHA256 =
            "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4";

    private YuNetModelAsset() {
    }

    static byte[] loadVerified(AssetManager assets) {
        Objects.requireNonNull(assets, "assets");
        try (InputStream input = assets.open(ASSET_PATH, AssetManager.ACCESS_BUFFER)) {
            byte[] model = readBounded(input);
            return verify(model);
        } catch (IOException exception) {
            throw new IllegalStateException("Bundled YuNet model is unavailable", exception);
        }
    }

    static byte[] verify(byte[] model) {
        Objects.requireNonNull(model, "model");
        if (model.length != EXPECTED_BYTES
                || !EXPECTED_SHA256.equals(sha256(model))) {
            throw new IllegalStateException("Bundled YuNet model provenance check failed");
        }
        return model;
    }

    static String sha256(byte[] value) {
        Objects.requireNonNull(value, "value");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(Character.forDigit((item >>> 4) & 0x0F, 16));
                hex.append(Character.forDigit(item & 0x0F, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Android must provide SHA-256", impossible);
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(EXPECTED_BYTES);
        byte[] chunk = new byte[8_192];
        int total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            total += read;
            if (total > EXPECTED_BYTES) {
                throw new IllegalStateException("Bundled YuNet model exceeds reviewed size");
            }
            output.write(chunk, 0, read);
        }
        return output.toByteArray();
    }
}
