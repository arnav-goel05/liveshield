package com.liveshield.app.session;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.camera.core.Preview;
import androidx.camera.view.PreviewView;
import com.liveshield.app.R;
import java.util.Objects;

/**
 * UI target for CameraX Preview after the mandatory privacy effect.
 *
 * <p>The returned provider must be supplied only to {@code CameraSessionController}, whose public
 * constructor rejects a video output not authorized by the exact privacy processor.</p>
 */
public final class RendererOwnedPreview implements LiveSessionCoordinator.SanitizedPreviewPort {
    private final FrameLayout container;
    private final PreviewView previewView;
    private boolean attached;

    public RendererOwnedPreview(FrameLayout container) {
        this.container = Objects.requireNonNull(container, "container");
        previewView = new PreviewView(container.getContext());
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        previewView.setContentDescription(
                container.getResources().getString(R.string.sanitized_preview_description));
    }

    public Preview.SurfaceProvider surfaceProvider() {
        return previewView.getSurfaceProvider();
    }

    @Override
    public void attach() {
        if (attached) {
            return;
        }
        View placeholder = container.findViewById(R.id.sanitized_preview_placeholder);
        if (placeholder != null) {
            placeholder.setVisibility(View.GONE);
        }
        container.addView(previewView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        attached = true;
    }

    @Override
    public void close() {
        if (!attached) {
            return;
        }
        container.removeView(previewView);
        View placeholder = container.findViewById(R.id.sanitized_preview_placeholder);
        if (placeholder != null) {
            placeholder.setVisibility(View.VISIBLE);
        }
        attached = false;
    }
}
