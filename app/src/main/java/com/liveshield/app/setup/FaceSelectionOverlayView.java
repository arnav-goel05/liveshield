package com.liveshield.app.setup;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import com.liveshield.app.R;
import java.util.List;

/** Draws selectable geometry over an already-sanitized preview; it never owns a camera surface. */
public final class FaceSelectionOverlayView extends View {
    private static final float STROKE_WIDTH_DP = 3.0f;
    private static final int AVAILABLE_COLOR = 0xFFE8EEF2;
    private static final int SELECTED_COLOR = 0xFF00C2A8;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<SelectableFace> faces = List.of();
    private Long selectedTrackId;
    private SelectionListener listener = ignored -> { };

    public FaceSelectionOverlayView(Context context, AttributeSet attributes) {
        super(context, attributes);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(STROKE_WIDTH_DP * getResources().getDisplayMetrics().density);
        setContentDescription(getResources().getString(R.string.face_selection_overlay_description));
    }

    public void showFaces(List<SelectableFace> newFaces, Long selectedTrack) {
        faces = List.copyOf(newFaces);
        selectedTrackId = selectedTrack;
        invalidate();
    }

    public void setSelectionListener(SelectionListener newListener) {
        listener = newListener == null ? ignored -> { } : newListener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (SelectableFace face : faces) {
            if (!face.fresh()) {
                continue;
            }
            paint.setColor(Long.valueOf(face.trackId()).equals(selectedTrackId)
                    ? SELECTED_COLOR : AVAILABLE_COLOR);
            canvas.drawRect(toPixels(face), paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP || !isEnabled()) {
            return true;
        }
        for (int index = faces.size() - 1; index >= 0; index--) {
            SelectableFace face = faces.get(index);
            if (face.fresh() && toPixels(face).contains(event.getX(), event.getY())) {
                performClick();
                listener.onFaceSelected(face.trackId());
                return true;
            }
        }
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
