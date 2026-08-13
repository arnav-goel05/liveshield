package com.liveshield.fixtures.pii;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Deterministic offline QR/Data Matrix generator for fictional test payloads only. */
public final class OfflineSymbolGenerator {
    // Large enough that lossy video transport still leaves multiple pixels per module.
    static final int OUTPUT_SIZE = 192;

    private OfflineSymbolGenerator() {
    }

    public static void main(String[] arguments) throws IOException, WriterException {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                    "Expected FORMAT, fictional payload, and output path");
        }
        BarcodeFormat format = supportedFormat(arguments[0]);
        writePbm(encode(format, arguments[1]), Path.of(arguments[2]));
    }

    static BitMatrix encode(BarcodeFormat format, String fictionalPayload)
            throws WriterException {
        if (fictionalPayload == null || fictionalPayload.isBlank()
                || fictionalPayload.length() > 128) {
            throw new IllegalArgumentException("Fictional symbol payload is outside bounds");
        }
        if (format != BarcodeFormat.QR_CODE && format != BarcodeFormat.DATA_MATRIX) {
            throw new IllegalArgumentException("Only QR_CODE and DATA_MATRIX are supported");
        }
        return new MultiFormatWriter().encode(
                fictionalPayload,
                format,
                OUTPUT_SIZE,
                OUTPUT_SIZE,
                Map.of(EncodeHintType.MARGIN, 4));
    }

    private static BarcodeFormat supportedFormat(String value) {
        return switch (value) {
            case "QR_CODE" -> BarcodeFormat.QR_CODE;
            case "DATA_MATRIX" -> BarcodeFormat.DATA_MATRIX;
            default -> throw new IllegalArgumentException("Unsupported symbol format");
        };
    }

    private static void writePbm(BitMatrix matrix, Path output) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                output, StandardCharsets.US_ASCII)) {
            writer.write("P1\n");
            writer.write(matrix.getWidth() + " " + matrix.getHeight() + "\n");
            for (int row = 0; row < matrix.getHeight(); row++) {
                for (int column = 0; column < matrix.getWidth(); column++) {
                    writer.write(matrix.get(column, row) ? "1" : "0");
                    writer.write(column + 1 == matrix.getWidth() ? '\n' : ' ');
                }
            }
        }
    }
}
