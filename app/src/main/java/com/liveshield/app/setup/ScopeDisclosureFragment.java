package com.liveshield.app.setup;

import android.content.Context;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.liveshield.app.R;

/** Static, payload-free disclosure that gates all camera and session setup. */
public final class ScopeDisclosureFragment extends Fragment {
    private static final int SLIDE_COUNT = 3;
    private static final int[] TITLES = {
            R.string.onboarding_video_title,
            R.string.onboarding_toolkit_title,
            R.string.onboarding_live_title
    };
    private static final int[] DETAILS = {
            R.string.onboarding_video_detail,
            R.string.onboarding_toolkit_detail,
            R.string.onboarding_live_detail
    };
    private static final int[] IMAGES = {
            R.drawable.onboarding_video_only,
            R.drawable.onboarding_privacy_toolkit,
            R.drawable.onboarding_go_live
    };
    private static final int[] IMAGE_DESCRIPTIONS = {
            R.string.onboarding_video_image_description,
            R.string.onboarding_toolkit_image_description,
            R.string.onboarding_live_image_description
    };
    private static final int[] DOTS = {
            R.id.onboarding_dot_one,
            R.id.onboarding_dot_two,
            R.id.onboarding_dot_three
    };
    private static final int[] TOOLKIT_PILLS = {
            R.id.onboarding_face_pill,
            R.id.onboarding_qr_pill,
            R.id.onboarding_text_pill,
            R.id.onboarding_zone_pill
    };
    private static final float MINIMUM_SWIPE_DP = 48f;
    private static final float MINIMUM_FLING_DP_PER_SECOND = 240f;

    /** Host callback invoked only after the creator explicitly acknowledges the disclosure. */
    public interface Listener {
        void onScopeDisclosureAccepted();
    }

    private Listener listener;
    private int currentSlide;

    public ScopeDisclosureFragment() {
        super(R.layout.fragment_scope_disclosure);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (!(context instanceof Listener)) {
            throw new IllegalStateException("Scope disclosure host must implement Listener");
        }
        listener = (Listener) context;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        configureCarousel(view);
        view.findViewById(R.id.acknowledge_scope_disclosure).setOnClickListener(ignored -> {
            View button = view.findViewById(R.id.acknowledge_scope_disclosure);
            button.setEnabled(false);
            if (listener != null) {
                listener.onScopeDisclosureAccepted();
            }
        });
    }

    private void configureCarousel(View root) {
        float density = getResources().getDisplayMetrics().density;
        GestureDetector gestures = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(@NonNull MotionEvent event) {
                        return true;
                    }

                    @Override
                    public boolean onFling(
                            @Nullable MotionEvent first,
                            @NonNull MotionEvent second,
                            float velocityX,
                            float velocityY) {
                        if (first == null) {
                            return false;
                        }
                        float horizontalDistance = second.getX() - first.getX();
                        if (Math.abs(horizontalDistance) < MINIMUM_SWIPE_DP * density
                                || Math.abs(velocityX)
                                < MINIMUM_FLING_DP_PER_SECOND * density
                                || Math.abs(horizontalDistance)
                                <= Math.abs(second.getY() - first.getY())) {
                            return false;
                        }
                        showSlide(root, currentSlide + (horizontalDistance < 0 ? 1 : -1), true);
                        return true;
                    }
                });
        root.setOnTouchListener((target, event) -> {
            boolean handled = gestures.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && !handled) {
                target.performClick();
            }
            return handled;
        });
        root.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                    View host,
                    AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (currentSlide < SLIDE_COUNT - 1) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                }
                if (currentSlide > 0) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                }
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        && currentSlide < SLIDE_COUNT - 1) {
                    showSlide(root, currentSlide + 1, true);
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD && currentSlide > 0) {
                    showSlide(root, currentSlide - 1, true);
                    return true;
                }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
        showSlide(root, 0, false);
    }

    private void showSlide(View root, int requestedSlide, boolean animate) {
        int nextSlide = Math.max(0, Math.min(requestedSlide, SLIDE_COUNT - 1));
        if (animate && nextSlide == currentSlide) {
            return;
        }
        View content = root.findViewById(R.id.onboarding_slide_content);
        Runnable update = () -> {
            currentSlide = nextSlide;
            TextView title = root.findViewById(R.id.scope_disclosure_title);
            TextView detail = root.findViewById(R.id.scope_disclosure_supported);
            ImageView image = root.findViewById(R.id.onboarding_hero);
            image.setClipToOutline(true);
            title.setText(TITLES[nextSlide]);
            detail.setText(DETAILS[nextSlide]);
            image.setImageResource(IMAGES[nextSlide]);
            image.setContentDescription(getString(IMAGE_DESCRIPTIONS[nextSlide]));
            for (int pill : TOOLKIT_PILLS) {
                root.findViewById(pill).setVisibility(nextSlide == 1 ? View.VISIBLE : View.GONE);
            }
            for (int index = 0; index < DOTS.length; index++) {
                root.findViewById(DOTS[index]).setBackgroundResource(
                        index == nextSlide
                                ? R.drawable.onboarding_dot_active
                                : R.drawable.onboarding_dot_inactive);
            }
            root.setContentDescription(getString(
                    R.string.onboarding_slide_description,
                    nextSlide + 1,
                    getString(TITLES[nextSlide])));
        };
        content.animate().cancel();
        if (!animate) {
            update.run();
            content.setAlpha(1f);
            return;
        }
        content.animate()
                .alpha(0f)
                .setDuration(90L)
                .withEndAction(() -> {
                    update.run();
                    content.animate().alpha(1f).setDuration(170L).start();
                    root.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                })
                .start();
    }

    @Override
    public void onDetach() {
        listener = null;
        super.onDetach();
    }
}
