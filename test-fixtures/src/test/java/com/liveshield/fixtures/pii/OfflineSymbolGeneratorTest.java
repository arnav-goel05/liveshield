package com.liveshield.fixtures.pii;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class OfflineSymbolGeneratorTest {
    @Test
    public void qrAndDataMatrixRoundTripThroughAuditedOfflineZxing() throws Exception {
        for (BarcodeFormat format : new BarcodeFormat[]{
            BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX}) {
            String fictionalPayload = "FICTIONAL-" + format.name() + "-204";
            BitMatrix first = OfflineSymbolGenerator.encode(format, fictionalPayload);
            BitMatrix second = OfflineSymbolGenerator.encode(format, fictionalPayload);

            assertEquals(first, second);
            Result decoded = new MultiFormatReader().decode(
                    new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(
                            first.getWidth(), first.getHeight(), pixels(first)))),
                    Map.of(
                            DecodeHintType.POSSIBLE_FORMATS, List.of(format),
                            DecodeHintType.PURE_BARCODE, Boolean.TRUE));
            assertEquals(format, decoded.getBarcodeFormat());
            assertEquals(fictionalPayload, decoded.getText());
        }
    }

    @Test
    public void generatedSymbolHasBothForegroundAndQuietBackground() throws Exception {
        BitMatrix matrix = OfflineSymbolGenerator.encode(
                BarcodeFormat.QR_CODE, "FICTIONAL-CONTROL");
        assertFalse(matrix.get(0, 0));
        assertFalse(matrix.get(matrix.getWidth() - 1, matrix.getHeight() - 1));
    }

    private static int[] pixels(BitMatrix matrix) {
        int[] pixels = new int[matrix.getWidth() * matrix.getHeight()];
        for (int row = 0; row < matrix.getHeight(); row++) {
            for (int column = 0; column < matrix.getWidth(); column++) {
                pixels[row * matrix.getWidth() + column] = matrix.get(column, row)
                        ? 0xff000000 : 0xffffffff;
            }
        }
        return pixels;
    }
}
