package com.liveshield.app.setup;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import androidx.annotation.OptIn;
import androidx.camera.view.PreviewView;
import androidx.camera.view.TransformExperimental;
import androidx.camera.view.transform.OutputTransform;
import com.liveshield.app.R;
import com.liveshield.app.diagnostics.AppDiagnostics;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.List;

/** Provides invisible hit targets over rendered masks; it never draws over the preview. */
@OptIn(markerClass = TransformExperimental.class)
public final class FaceSelectionOverlayView extends View {
    private List<SelectableFace> faces = List.of();
    private List<DismissiblePrivacyMask> privacyMasks = List.of();
    private SelectionListener listener = ignored -> { };
    private DismissalListener dismissalListener = ignored -> { };

    public FaceSelectionOverlayView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setWillNotDraw(true);
        setClickable(true);
        setFocusable(true);
        setContentDescription(getResources().getString(R.string.face_selection_overlay_description));
    }

    public void showFaces(List<SelectableFace> newFaces, Long selectedTrack) {
        faces = List.copyOf(newFaces);
        boolean selectedFresh = selectedTrack != null && faces.stream().anyMatch(
                face -> face.fresh() && face.trackId() == selectedTrack.longValue());
        setSelected(selectedFresh);
        setContentDescription(getResources().getString(selectedFresh
                ? R.string.face_selection_overlay_selected_description
                : R.string.face_selection_overlay_description));
    }

    public void setSelectionListener(SelectionListener newListener) {
        listener = newListener == null ? ignored -> { } : newListener;
    }

    public void showDismissiblePrivacyMasks(List<DismissiblePrivacyMask> newMasks) {
        privacyMasks = List.copyOf(newMasks);
    }

    public void setDismissalListener(DismissalListener newListener) {
        dismissalListener = newListener == null ? ignored -> { } : newListener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP || !isEnabled()) {
            return true;
        }
        AppDiagnostics.dimensions(
                AppDiagnostics.Event.PRIVACY_MASK_TAP_POINT,
                Math.round(event.getX()), Math.round(event.getY()));
        for (int index = privacyMasks.size() - 1; index >= 0; index--) {
            DismissiblePrivacyMask mask = privacyMasks.get(index);
            RectF pixels = toPixels(mask.bounds());
            if (pixels != null) {
                AppDiagnostics.pixelBounds(
                        AppDiagnostics.Event.PRIVACY_MASK_HIT_BOUNDS,
                        mask.category().ordinal(),
                        Math.round(pixels.left), Math.round(pixels.top),
                        Math.round(pixels.right), Math.round(pixels.bottom));
            }
            if (pixels != null && pixels.contains(event.getX(), event.getY())) {
                AppDiagnostics.state(
                        AppDiagnostics.Event.PRIVACY_MASK_TAP_HIT, mask.category());
                performClick();
                privacyMasks = privacyMasks.stream()
                        .filter(candidate -> candidate.category() != mask.category())
                        .toList();
                dismissalListener.onPrivacyMaskDismissed(mask.category());
                return true;
            }
        }
        if (!privacyMasks.isEmpty()) {
            AppDiagnostics.info(AppDiagnostics.Event.PRIVACY_MASK_TAP_MISS);
        }
        for (int index = faces.size() - 1; index >= 0; index--) {
            SelectableFace face = faces.get(index);
            RectF pixels = toPixels(face.outputBounds());
            if (face.fresh() && pixels != null
                    && pixels.contains(event.getX(), event.getY())) {
                AppDiagnostics.info(AppDiagnostics.Event.FACE_REGION_TAP_HIT);
                performClick();
                listener.onFaceSelected(face.trackId());
                return true;
            }
        }
        AppDiagnostics.info(AppDiagnostics.Event.FACE_REGION_TAP_MISS);
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /** Applies CameraX's authoritative FIT_CENTER/crop/rotation/mirror output transform. */
    @SuppressLint("RestrictedApi")
    private RectF toPixels(NormalizedRect bounds) {
        ViewParent parent = getParent();
        if (!(parent instanceof ViewGroup siblings)) {
            return null;
        }
        for (int index = 0; index < siblings.getChildCount(); index++) {
            if (!(siblings.getChildAt(index) instanceof PreviewView preview)) {
                continue;
            }
            OutputTransform output = preview.getOutputTransform();
            if (output == null) {
                return null;
            }
            Matrix outputToOverlay = new Matrix(output.getMatrix());
            outputToOverlay.postTranslate(
                    preview.getLeft() - getLeft(), preview.getTop() - getTop());
            RectF pixels = new RectF(
                    normalizedOutput(bounds.left()),
                    normalizedOutput(bounds.top()),
                    normalizedOutput(bounds.right()),
                    normalizedOutput(bounds.bottom()));
            outputToOverlay.mapRect(pixels);
            return pixels;
        }
        return null;
    }

    private static float normalizedOutput(double value) {
        return (float) (value * 2.0 - 1.0);
    }

    @FunctionalInterface
    public interface SelectionListener {
        void onFaceSelected(long trackId);
    }

    @FunctionalInterface
    public interface DismissalListener {
        void onPrivacyMaskDismissed(FindingCategory category);
    }
}
