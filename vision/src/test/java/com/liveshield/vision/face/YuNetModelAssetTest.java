package com.liveshield.vision.face;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import org.junit.Test;

public final class YuNetModelAssetTest {
    private static final File MODEL = new File(
            "src/main/assets/models/face_detection_yunet_2023mar.onnx");

    @Test
    public void pinnedModelMatchesReviewedSizeAndDigest() throws IOException {
        byte[] model = readModel();

        YuNetModelAsset.verify(model);

        assertEquals(YuNetModelAsset.EXPECTED_BYTES, model.length);
        assertEquals(YuNetModelAsset.EXPECTED_SHA256, YuNetModelAsset.sha256(model));
    }

    @Test
    public void changedModelIsRejected() throws IOException {
        byte[] model = readModel();
        model[0] ^= 1;

        assertThrows(IllegalStateException.class, () -> YuNetModelAsset.verify(model));
    }

    private static byte[] readModel() throws IOException {
        try (FileInputStream input = new FileInputStream(MODEL);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8_192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        }
    }
}
