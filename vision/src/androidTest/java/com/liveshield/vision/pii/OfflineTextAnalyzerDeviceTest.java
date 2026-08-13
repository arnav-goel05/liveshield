package com.liveshield.vision.pii;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

/** ARM64 native smoke only; accuracy and BIV metrics are deliberately outside T093. */
@RunWith(AndroidJUnit4.class)
public final class OfflineTextAnalyzerDeviceTest {
    @Test
    public void bundledModelsInitializeAndSyntheticFrameCompletesOffline() throws Exception {
        assumeTrue(Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"));
        Context context = ApplicationProvider.getApplicationContext();
        PaddleOcrAssets.verifyBundledAssets(context);
        OfflineTextAnalyzer analyzer = new OfflineTextAnalyzer(
                context, OfflineTextAnalyzer.Configuration.defaults(Set.of()));
        Bitmap bitmap = Bitmap.createBitmap(640, 240, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(64.0F);
        canvas.drawText("OTP 123456", 30.0F, 140.0F, paint);
        BitmapFrame frame = new BitmapFrame(bitmap);
        try {
            DetectorSnapshot result = analyzer.analyze(
                    frame,
                    FrameTimestamp.ofNanos(1_000),
                    0,
                    CoordinateTransform.identity()).get(30, TimeUnit.SECONDS);
            assertEquals(DetectorLane.TEXT, result.lane());
            assertTrue(result.failure().isEmpty());
            assertTrue(frame.closed.get());
        } finally {
            analyzer.close();
        }
    }

    private static final class BitmapFrame implements TextAnalysisFrame {
        private final Bitmap bitmap;
        private final AtomicBoolean closed = new AtomicBoolean();

        private BitmapFrame(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public Bitmap bitmap(int rotationDegrees) {
            if (closed.get()) {
                throw new IllegalStateException("closed");
            }
            return bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }

        @Override
        public int width() {
            return bitmap.getWidth();
        }

        @Override
        public int height() {
            return bitmap.getHeight();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                bitmap.recycle();
            }
        }
    }
}
