package com.liveshield.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import com.liveshield.app.session.LiveActivity;
import com.liveshield.app.setup.SetupActivity;
import com.liveshield.privacy.session.SessionState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Accessibility contracts for labels, reading order, contrast, and private health announcements. */
@LargeTest
@RunWith(AndroidJUnit4.class)
public final class AccessibilityTest {
    private static final int WINDOW_BACKGROUND = Color.rgb(11, 15, 18);
    private static final int DISCLOSURE_BACKGROUND = Color.rgb(16, 24, 32);
    private static final int STATUS_CARD_BACKGROUND = Color.rgb(16, 20, 24);
    private static final double NORMAL_TEXT_CONTRAST = 4.5;

    @Test
    public void disclosureReadingOrderLabelsAndContrastAreAccessible() {
        try (ActivityScenario<SetupActivity> scenario = ActivityScenario.launch(SetupActivity.class)) {
            scenario.onActivity(activity -> {
                View root = activity.findViewById(R.id.scope_disclosure_container);
                List<TextView> spoken = textViews(root);
                assertFalse(spoken.isEmpty());
                for (TextView text : spoken) {
                    assertFalse("Visible disclosure text must be nonempty",
                            text.getText().toString().trim().isEmpty());
                    assertTrue("Disclosure text contrast is below 4.5:1",
                            contrast(text.getCurrentTextColor(), DISCLOSURE_BACKGROUND)
                                    >= NORMAL_TEXT_CONTRAST);
                }
                Button acknowledgement = root.findViewById(R.id.acknowledge_scope_disclosure);
                assertNotNull(acknowledgement);
                assertFalse(acknowledgement.getText().toString().trim().isEmpty());
                assertTraversalAfter(root, R.id.scope_disclosure_supported,
                        R.id.scope_disclosure_title);
                assertTraversalAfter(root, R.id.scope_disclosure_visual_only,
                        R.id.scope_disclosure_supported);
                assertTraversalAfter(root, R.id.scope_disclosure_no_anonymity,
                        R.id.scope_disclosure_visual_only);
                assertTraversalAfter(root, R.id.scope_disclosure_unsupported,
                        R.id.scope_disclosure_no_anonymity);
                assertTraversalAfter(root, R.id.acknowledge_scope_disclosure,
                        R.id.scope_disclosure_unsupported);
                assertEquals("Acknowledgement must follow all explanatory text",
                        acknowledgement, lastFocusable(root));
            });
        }
    }

    @Test
    public void healthStateIsAnAssertiveNonvisualAnnouncementWithSafeFocusOrder() {
        try (ActivityScenario<LiveActivity> scenario = ActivityScenario.launch(LiveActivity.class)) {
            scenario.onActivity(activity -> {
                View statusCard = activity.findViewById(R.id.live_status_card);
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE,
                        statusCard.getAccessibilityLiveRegion());
                assertTrue(statusCard.isFocusable());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                        activity.findViewById(R.id.live_status_indicator)
                                .getImportantForAccessibility());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                        activity.findViewById(R.id.live_status_label)
                                .getImportantForAccessibility());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                        activity.findViewById(R.id.live_status_detail)
                                .getImportantForAccessibility());
            });

            UiAutomation automation = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation();
            String packageName = InstrumentationRegistry.getInstrumentation()
                    .getTargetContext().getPackageName();
            String expectedStatus = InstrumentationRegistry.getInstrumentation()
                    .getTargetContext().getString(R.string.live_status_degraded_label)
                    + ". "
                    + InstrumentationRegistry.getInstrumentation()
                            .getTargetContext().getString(R.string.live_status_degraded_detail);
            waitForAccessibilityIdle(automation);
            AtomicInteger changedEvents = new AtomicInteger();
            CountDownLatch changedEvent = new CountDownLatch(1);
            automation.setOnAccessibilityEventListener(event -> {
                if (isStatusContentChange(event, packageName, expectedStatus)) {
                    changedEvents.incrementAndGet();
                    changedEvent.countDown();
                }
            });
            try {
                scenario.onActivity(activity -> activity.showSessionState(SessionState.DEGRADED));
                await(changedEvent, 3_000L,
                        "Changed health state did not dispatch its live-region content event");
                // Android may emit a burst for a live-region node and its changed descendants.
                // Drain that complete burst before attributing later events to an unchanged call.
                waitForAccessibilityIdle(automation);
            } finally {
                automation.setOnAccessibilityEventListener(null);
            }
            assertTrue("A changed state must expose its complete live-region status",
                    changedEvents.get() > 0);

            AtomicInteger unchangedEvents = new AtomicInteger();
            CountDownLatch unchangedEvent = new CountDownLatch(1);
            automation.setOnAccessibilityEventListener(event -> {
                if (isStatusContentChange(event, packageName, expectedStatus)) {
                    unchangedEvents.incrementAndGet();
                    unchangedEvent.countDown();
                }
            });
            try {
                scenario.onActivity(activity -> activity.showSessionState(SessionState.DEGRADED));
                assertFalse("An unchanged health state repeated its live-region content event",
                        awaitEvent(unchangedEvent, 750L));
            } finally {
                automation.setOnAccessibilityEventListener(null);
            }
            assertEquals(0, unchangedEvents.get());

            scenario.onActivity(activity -> {
                View statusCard = activity.findViewById(R.id.live_status_card);
                Button stop = activity.findViewById(R.id.stop_live_session);
                String spoken = statusCard.getContentDescription().toString();
                assertTrue(spoken.contains(activity.getString(R.string.live_status_degraded_label)));
                assertTrue(spoken.contains(activity.getString(R.string.live_status_degraded_detail)));
                assertTrue(stop.isEnabled());
                assertFalse(stop.getText().toString().trim().isEmpty());
                View root = activity.findViewById(R.id.live_private_controls_root);
                assertTraversalAfter(root, R.id.live_private_controls_notice,
                        R.id.live_screen_title);
                assertTraversalAfter(root, R.id.live_status_card,
                        R.id.live_private_controls_notice);
                assertTraversalAfter(root, R.id.live_publisher_status,
                        R.id.live_status_card);
                assertTraversalAfter(root, R.id.live_privacy_limit_notice,
                        R.id.live_publisher_status);
                assertTraversalAfter(root, R.id.live_video_only_notice,
                        R.id.live_privacy_limit_notice);
                assertTraversalAfter(root, R.id.stop_live_session,
                        R.id.live_video_only_notice);
                assertEquals(stop, lastFocusable(activity.findViewById(
                        R.id.live_private_controls_root)));
                for (TextView text : textViews(activity.findViewById(
                        R.id.live_private_controls_root))) {
                    int background = isDescendantOf(text, statusCard)
                            ? STATUS_CARD_BACKGROUND : WINDOW_BACKGROUND;
                    assertTrue("Live-status text contrast is below 4.5:1",
                            contrast(text.getCurrentTextColor(), background)
                                    >= NORMAL_TEXT_CONTRAST);
                }
            });
        }
    }

    private static boolean isStatusContentChange(
            AccessibilityEvent event, String packageName, String expectedStatus) {
        return event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && event.getPackageName() != null
                && packageName.contentEquals(event.getPackageName())
                && event.getClassName() != null
                && LinearLayout.class.getName().contentEquals(event.getClassName())
                && event.getContentDescription() != null
                && expectedStatus.contentEquals(event.getContentDescription());
    }

    private static void waitForAccessibilityIdle(UiAutomation automation) {
        try {
            automation.waitForIdle(250L, 3_000L);
        } catch (TimeoutException timeout) {
            throw new AssertionError("Accessibility event stream did not become idle", timeout);
        }
    }

    private static void await(CountDownLatch latch, long timeoutMs, String failure) {
        if (!awaitEvent(latch, timeoutMs)) {
            throw new AssertionError(failure);
        }
    }

    private static boolean awaitEvent(CountDownLatch latch, long timeoutMs) {
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while observing accessibility events", interrupted);
        }
    }

    private static List<TextView> textViews(View root) {
        List<TextView> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(View view, List<TextView> result) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view instanceof TextView text && !(view instanceof Button)) {
            result.add(text);
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                collect(group.getChildAt(index), result);
            }
        }
    }

    private static View lastFocusable(View root) {
        List<View> focusable = new ArrayList<>();
        collectFocusable(root, focusable);
        assertFalse("Screen must expose at least one focusable control", focusable.isEmpty());
        return focusable.get(focusable.size() - 1);
    }

    private static void collectFocusable(View view, List<View> result) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view.isFocusable() && view.isEnabled()) {
            result.add(view);
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                collectFocusable(group.getChildAt(index), result);
            }
        }
    }

    private static boolean isDescendantOf(View child, View ancestor) {
        View current = child;
        while (current.getParent() instanceof View parent) {
            if (parent == ancestor) {
                return true;
            }
            current = parent;
        }
        return false;
    }

    private static void assertTraversalAfter(View root, int childId, int predecessorId) {
        View child = root.findViewById(childId);
        assertNotNull(child);
        assertEquals(predecessorId, child.getAccessibilityTraversalAfter());
    }

    private static double contrast(int foreground, int background) {
        double lighter = Math.max(luminance(foreground), luminance(background));
        double darker = Math.min(luminance(foreground), luminance(background));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * channel(Color.red(color))
                + 0.7152 * channel(Color.green(color))
                + 0.0722 * channel(Color.blue(color));
    }

    private static double channel(int value) {
        double normalized = value / 255.0;
        return normalized <= 0.03928
                ? normalized / 12.92
                : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }
}
