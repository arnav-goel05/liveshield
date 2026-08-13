package com.liveshield.app.setup;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
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
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int index = 0; index < zones.size(); index++) {
            NormalizedRect zone = zones.get(index);
            canvas.drawRect(toPixels(zone), index == selectedIndex ? selectedPaint : zonePaint);
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
        float x = clamp(event.getX(), 0.0f, getWidth());
        float y = clamp(event.getY(), 0.0f, getHeight());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
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
                if (completed.width() / getWidth() >= MINIMUM_TOUCH_SPAN
                        && completed.height() / getHeight() >= MINIMUM_TOUCH_SPAN) {
                    listener.onZoneDrawn(new NormalizedRect(
                            completed.left / getWidth(),
                            completed.top / getHeight(),
                            completed.right / getWidth(),
                            completed.bottom / getHeight()));
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                draft = null;
                invalidate();
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

    private RectF toPixels(NormalizedRect zone) {
        return new RectF(
                (float) zone.left() * getWidth(),
                (float) zone.top() * getHeight(),
                (float) zone.right() * getWidth(),
                (float) zone.bottom() * getHeight());
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
