package com.liveshield.app.setup;

import android.content.Context;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import com.liveshield.app.R;
import com.liveshield.app.diagnostics.AppDiagnostics;
import java.util.List;

/** Provides invisible hit targets over face masks; it never draws over the sanitized preview. */
public final class FaceSelectionOverlayView extends View {
    private List<SelectableFace> faces = List.of();
    private SelectionListener listener = ignored -> { };

    public FaceSelectionOverlayView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setWillNotDraw(true);
        setContentDescription(getResources().getString(R.string.face_selection_overlay_description));
    }

    public void showFaces(List<SelectableFace> newFaces, Long selectedTrack) {
        faces = List.copyOf(newFaces);
    }

    public void setSelectionListener(SelectionListener newListener) {
        listener = newListener == null ? ignored -> { } : newListener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP || !isEnabled()) {
            return true;
        }
        for (int index = faces.size() - 1; index >= 0; index--) {
            SelectableFace face = faces.get(index);
            if (face.fresh() && toPixels(face).contains(event.getX(), event.getY())) {
                AppDiagnostics.info(AppDiagnostics.Event.FACE_MASK_TAP_HIT);
                performClick();
                listener.onFaceSelected(face.trackId());
                return true;
            }
        }
        AppDiagnostics.info(AppDiagnostics.Event.FACE_MASK_TAP_MISS);
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private RectF toPixels(SelectableFace face) {
        return new RectF(
                (float) (face.outputBounds().left() * getWidth()),
                (float) (face.outputBounds().top() * getHeight()),
                (float) (face.outputBounds().right() * getWidth()),
                (float) (face.outputBounds().bottom() * getHeight()));
    }

    @FunctionalInterface
    public interface SelectionListener {
        void onFaceSelected(long trackId);
    }
}
