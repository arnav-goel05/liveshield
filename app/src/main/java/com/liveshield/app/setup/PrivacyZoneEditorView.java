package com.liveshield.app.setup;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import androidx.camera.view.PreviewView;
import com.liveshield.app.R;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.List;

/** Private, pixel-free overlay for drawing fixed normalized protection zones. */
public final class PrivacyZoneEditorView extends View {
    private static final float MINIMUM_TOUCH_SPAN = 0.01f;
    private final Paint zonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<NormalizedRect> zones = List.of();
    private int selectedIndex = -1;
    private float startX;
    private float startY;
    private RectF draft;
    private RectF cachedContentViewport;
    private ZoneDrawListener listener = ignored -> { };

    public PrivacyZoneEditorView(Context context, AttributeSet attributes) {
        super(context, attributes);
        zonePaint.setColor(0xCCFFD54F);
        zonePaint.setStyle(Paint.Style.STROKE);
        zonePaint.setStrokeWidth(4.0f);
        selectedPaint.setColor(0xFF80CBC4);
        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(6.0f);
        setFocusable(true);
        setClickable(true);
        setContentDescription(context.getString(R.string.privacy_zone_canvas_description));
    }

    public void setZoneDrawListener(ZoneDrawListener newListener) {
        listener = newListener == null ? ignored -> { } : newListener;
    }

    public void showZones(List<NormalizedRect> updated, int selected) {
        zones = List.copyOf(updated);
        selectedIndex = selected >= 0 && selected < zones.size() ? selected : -1;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        cachedContentViewport = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        RectF viewport = contentViewport();
        if (viewport == null) {
            return;
        }
        for (int index = 0; index < zones.size(); index++) {
            NormalizedRect zone = zones.get(index);
            canvas.drawRect(toPixels(zone, viewport),
                    index == selectedIndex ? selectedPaint : zonePaint);
        }
        if (draft != null) {
            canvas.drawRect(draft, selectedPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || getWidth() == 0 || getHeight() == 0) {
            return false;
        }
        RectF viewport = contentViewport();
        if (viewport == null || viewport.isEmpty()) {
            return false;
        }
        float x = clamp(event.getX(), viewport.left, viewport.right);
        float y = clamp(event.getY(), viewport.top, viewport.bottom);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                // This editor lives inside the vertically scrolling setup screen. Once a zone
                // gesture begins, keep the ScrollView from stealing ACTION_MOVE and replacing
                // the intended rectangle with a cancelled, momentary draft.
                getParent().requestDisallowInterceptTouchEvent(true);
                startX = x;
                startY = y;
                draft = new RectF(x, y, x, y);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                draft = ordered(startX, startY, x, y);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                RectF completed = ordered(startX, startY, x, y);
                draft = null;
                invalidate();
                performClick();
                if (completed.width() / viewport.width() >= MINIMUM_TOUCH_SPAN
                        && completed.height() / viewport.height() >= MINIMUM_TOUCH_SPAN) {
                    listener.onZoneDrawn(new NormalizedRect(
                            (completed.left - viewport.left) / viewport.width(),
                            (completed.top - viewport.top) / viewport.height(),
                            (completed.right - viewport.left) / viewport.width(),
                            (completed.bottom - viewport.top) / viewport.height()));
                }
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                draft = null;
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

    private static RectF toPixels(NormalizedRect zone, RectF viewport) {
        return new RectF(
                viewport.left + (float) zone.left() * viewport.width(),
                viewport.top + (float) zone.top() * viewport.height(),
                viewport.left + (float) zone.right() * viewport.width(),
                viewport.top + (float) zone.bottom() * viewport.height());
    }

    /** Returns the transformed camera content, retaining full bounds while the view is scrolled. */
    private RectF contentViewport() {
        ViewParent parent = getParent();
        if (parent instanceof ViewGroup) {
            ViewGroup siblings = (ViewGroup) parent;
            for (int index = 0; index < siblings.getChildCount(); index++) {
                if (!(siblings.getChildAt(index) instanceof PreviewView)) {
                    continue;
                }
                PreviewView preview = (PreviewView) siblings.getChildAt(index);
                if (preview.getChildCount() == 0) {
                    return null;
                }
                View content = preview.getChildAt(0);
                if (content.getWidth() <= 0 || content.getHeight() <= 0) {
                    return null;
                }
                Rect previewVisible = new Rect();
                boolean previewIsFullyVisible = preview.getGlobalVisibleRect(previewVisible)
                        && previewVisible.width() == preview.getWidth()
                        && previewVisible.height() == preview.getHeight();
                if (previewIsFullyVisible) {
                    Rect displayed = new Rect();
                    int[] editorLocation = new int[2];
                    if (content.getGlobalVisibleRect(displayed)) {
                        getLocationOnScreen(editorLocation);
                        cachedContentViewport = new RectF(
                                displayed.left - editorLocation[0],
                                displayed.top - editorLocation[1],
                                displayed.right - editorLocation[0],
                                displayed.bottom - editorLocation[1]);
                    }
                }
                return cachedContentViewport == null
                        ? null : new RectF(cachedContentViewport);
            }
        }
        return null;
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
}
