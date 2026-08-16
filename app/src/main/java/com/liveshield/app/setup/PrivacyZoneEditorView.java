package com.liveshield.app.setup;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import androidx.camera.view.PreviewView;
import androidx.camera.view.TransformExperimental;
import androidx.camera.view.transform.OutputTransform;
import com.liveshield.app.R;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.List;

/** Private, pixel-free overlay for drawing fixed normalized protection zones. */
@TransformExperimental
public final class PrivacyZoneEditorView extends View {
    private static final float MINIMUM_TOUCH_SPAN = 0.01f;
    private final Paint zonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float crossRadius;
    private final float crossHitRadius;
    private List<NormalizedRect> zones = List.of();
    private int pendingRemovalIndex = -1;
    private float startX;
    private float startY;
    private RectF draft;
    private PreviewTransform gestureTransform;
    private ZoneDrawListener listener = ignored -> { };
    private ZoneRemoveListener removeListener = ignored -> { };

    public PrivacyZoneEditorView(Context context, AttributeSet attributes) {
        super(context, attributes);
        zonePaint.setColor(0xCCFFD54F);
        zonePaint.setStyle(Paint.Style.STROKE);
        zonePaint.setStrokeWidth(4.0f);
        crossBackgroundPaint.setColor(0xE6000000);
        crossBackgroundPaint.setStyle(Paint.Style.FILL);
        crossPaint.setColor(0xFFFFFFFF);
        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStrokeCap(Paint.Cap.ROUND);
        crossPaint.setStrokeWidth(3.0f * getResources().getDisplayMetrics().density);
        crossRadius = 18.0f * getResources().getDisplayMetrics().density;
        crossHitRadius = 24.0f * getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        setContentDescription(context.getString(R.string.privacy_zone_canvas_description));
    }

    public void setZoneDrawListener(ZoneDrawListener newListener) {
        listener = newListener == null ? ignored -> { } : newListener;
    }

    public void setZoneRemoveListener(ZoneRemoveListener newListener) {
        removeListener = newListener == null ? ignored -> { } : newListener;
    }

    public void showZones(List<NormalizedRect> updated) {
        zones = List.copyOf(updated);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        PreviewTransform transform = previewTransform();
        if (transform == null) {
            return;
        }
        for (int index = 0; index < zones.size(); index++) {
            NormalizedRect zone = zones.get(index);
            RectF pixels = toPixels(zone, transform);
            canvas.drawRect(pixels, zonePaint);
            drawRemoveControl(canvas, pixels);
        }
        if (draft != null) {
            canvas.drawRect(draft, zonePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || getWidth() == 0 || getHeight() == 0) {
            return false;
        }
        PreviewTransform current = gestureTransform == null ? previewTransform() : gestureTransform;
        if (current == null || current.viewport().isEmpty()) {
            return false;
        }
        RectF viewport = current.viewport();
        float x = clamp(event.getX(), viewport.left, viewport.right);
        float y = clamp(event.getY(), viewport.top, viewport.bottom);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                // This editor lives inside the vertically scrolling setup screen. Once a zone
                // gesture begins, keep the ScrollView from stealing ACTION_MOVE and replacing
                // the intended rectangle with a cancelled, momentary draft.
                getParent().requestDisallowInterceptTouchEvent(true);
                gestureTransform = current;
                pendingRemovalIndex = removeControlAt(x, y, current);
                if (pendingRemovalIndex >= 0) {
                    return true;
                }
                startX = x;
                startY = y;
                draft = new RectF(x, y, x, y);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (pendingRemovalIndex >= 0) {
                    return true;
                }
                draft = ordered(startX, startY, x, y);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                if (pendingRemovalIndex >= 0) {
                    int removalIndex = pendingRemovalIndex;
                    pendingRemovalIndex = -1;
                    gestureTransform = null;
                    performClick();
                    removeListener.onZoneRemoved(removalIndex);
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                RectF completed = ordered(startX, startY, x, y);
                draft = null;
                invalidate();
                performClick();
                if (completed.width() / viewport.width() >= MINIMUM_TOUCH_SPAN
                        && completed.height() / viewport.height() >= MINIMUM_TOUCH_SPAN) {
                    listener.onZoneDrawn(toOutputCoordinates(completed, current));
                }
                gestureTransform = null;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                draft = null;
                pendingRemovalIndex = -1;
                gestureTransform = null;
                invalidate();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            default -> {
                return super.onTouchEvent(event);
            }
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private static RectF toPixels(NormalizedRect zone, PreviewTransform transform) {
        RectF pixels = new RectF(
                normalizedOutput(zone.left()), normalizedOutput(zone.top()),
                normalizedOutput(zone.right()), normalizedOutput(zone.bottom()));
        transform.outputToEditor().mapRect(pixels);
        return pixels;
    }

    private void drawRemoveControl(Canvas canvas, RectF zone) {
        float centerX = zone.right;
        float centerY = zone.top;
        float arm = crossRadius * 0.45f;
        canvas.drawCircle(centerX, centerY, crossRadius, crossBackgroundPaint);
        canvas.drawLine(centerX - arm, centerY - arm, centerX + arm, centerY + arm, crossPaint);
        canvas.drawLine(centerX + arm, centerY - arm, centerX - arm, centerY + arm, crossPaint);
    }

    private int removeControlAt(float x, float y, PreviewTransform transform) {
        for (int index = zones.size() - 1; index >= 0; index--) {
            RectF zone = toPixels(zones.get(index), transform);
            float dx = x - zone.right;
            float dy = y - zone.top;
            if (dx * dx + dy * dy <= crossHitRadius * crossHitRadius) {
                return index;
            }
        }
        return -1;
    }

    private static NormalizedRect toOutputCoordinates(
            RectF editorRect, PreviewTransform transform) {
        RectF output = new RectF(editorRect);
        transform.editorToOutput().mapRect(output);
        return new NormalizedRect(
                clampNormalized((output.left + 1.0f) * 0.5f),
                clampNormalized((output.top + 1.0f) * 0.5f),
                clampNormalized((output.right + 1.0f) * 0.5f),
                clampNormalized((output.bottom + 1.0f) * 0.5f));
    }

    private static float normalizedOutput(double value) {
        return (float) (value * 2.0 - 1.0);
    }

    /** Maps CameraX output coordinates to this overlay, including crop, rotation and mirroring. */
    @SuppressLint("RestrictedApi")
    private PreviewTransform previewTransform() {
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
            Matrix outputToEditor = new Matrix(output.getMatrix());
            outputToEditor.postTranslate(preview.getLeft() - getLeft(), preview.getTop() - getTop());
            Matrix editorToOutput = new Matrix();
            if (!outputToEditor.invert(editorToOutput)) {
                return null;
            }
            RectF viewport = new RectF(-1.0f, -1.0f, 1.0f, 1.0f);
            outputToEditor.mapRect(viewport);
            if (!viewport.intersect(0.0f, 0.0f, getWidth(), getHeight())
                    || viewport.isEmpty()) {
                return null;
            }
            return new PreviewTransform(outputToEditor, editorToOutput, viewport);
        }
        return null;
    }

    private static double clampNormalized(float value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static RectF ordered(float firstX, float firstY, float secondX, float secondY) {
        return new RectF(
                Math.min(firstX, secondX),
                Math.min(firstY, secondY),
                Math.max(firstX, secondX),
                Math.max(firstY, secondY));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @FunctionalInterface
    public interface ZoneDrawListener {
        void onZoneDrawn(NormalizedRect zone);
    }

    @FunctionalInterface
    public interface ZoneRemoveListener {
        void onZoneRemoved(int index);
    }

    private record PreviewTransform(
            Matrix outputToEditor, Matrix editorToOutput, RectF viewport) { }
}
