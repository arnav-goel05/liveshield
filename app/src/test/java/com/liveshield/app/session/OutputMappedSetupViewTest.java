package com.liveshield.app.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.liveshield.app.setup.SelectableFace;
import com.liveshield.app.setup.SetupView;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.video.geometry.FrameTransform;
import java.util.List;
import org.junit.Test;

public final class OutputMappedSetupViewTest {
    @Test
    public void selectableFaceUsesSameSensorToOutputMappingAsPrivacyRenderer() {
        RecordingView delegate = new RecordingView();
        FrameTransform transform = FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(),
                new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                90,
                true);
        OutputMappedSetupView view = new OutputMappedSetupView(delegate, () -> transform);

        view.showSelectableFaces(List.of(new SelectableFace(
                7L, new NormalizedRect(0.10, 0.20, 0.30, 0.40), true)), 7L);

        assertEquals(List.of(new SelectableFace(
                7L, transform.mapSensorRectToOutput(
                        new NormalizedRect(0.10, 0.20, 0.30, 0.40)), true)), delegate.faces);
        assertEquals(Long.valueOf(7L), delegate.selectedTrack);
    }

    @Test
    public void missingTransformHidesOverlayInsteadOfDrawingWrongCoordinates() {
        RecordingView delegate = new RecordingView();
        OutputMappedSetupView view = new OutputMappedSetupView(delegate, () -> null);

        view.showSelectableFaces(List.of(new SelectableFace(
                3L, new NormalizedRect(0.10, 0.20, 0.30, 0.40), true)), 3L);

        assertEquals(List.of(), delegate.faces);
        assertNull(delegate.selectedTrack);
    }

    private static final class RecordingView implements SetupView {
        private List<SelectableFace> faces = List.of();
        private Long selectedTrack;

        @Override
        public void showSelectableFaces(List<SelectableFace> value, Long selected) {
            faces = value;
            selectedTrack = selected;
        }

        @Override
        public void showPrivacyReady(boolean ready) {
        }
    }
}
