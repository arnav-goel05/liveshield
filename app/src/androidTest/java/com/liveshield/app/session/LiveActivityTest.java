package com.liveshield.app.session;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import androidx.camera.view.PreviewView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.liveshield.app.R;
import com.liveshield.privacy.session.SessionState;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class LiveActivityTest {
    @Test
    public void statusTransitionsStayPrivateExplicitAndActionable() {
        LiveSessionUiRegistry.resetForTest();
        try (ActivityScenario<LiveActivity> scenario =
                     ActivityScenario.launch(LiveActivity.class)) {
            onView(withId(R.id.live_status_label))
                    .check(matches(withText(R.string.live_status_not_live_label)));
            onView(withId(R.id.stop_live_session)).check(matches(not(isEnabled())));

            LiveSessionUiRegistry.activate(SessionState.LIVE, () -> { });
            scenario.onActivity(activity -> activity.showSessionState(SessionState.LIVE));
            onView(withId(R.id.live_status_label))
                    .check(matches(withText(R.string.live_status_healthy_label)));
            onView(withId(R.id.stop_live_session)).check(matches(isEnabled()));

            scenario.onActivity(activity -> activity.showSessionState(SessionState.DEGRADED));
            LiveSessionUiRegistry.update(SessionState.DEGRADED);
            onView(withId(R.id.live_status_label))
                    .check(matches(withText(R.string.live_status_degraded_label)));
            scenario.recreate();
            onView(withId(R.id.live_status_label))
                    .check(matches(withText(R.string.live_status_degraded_label)));

            scenario.onActivity(activity -> activity.showSessionState(SessionState.SHIELDING));
            onView(withId(R.id.live_status_label))
                    .check(matches(withText(R.string.live_status_shielding_label)));

            scenario.onActivity(activity -> activity.showSessionState(SessionState.FAILED));
            onView(withId(R.id.live_status_label))
                    .check(matches(withText(R.string.live_status_failed_label)));
            onView(withId(R.id.stop_live_session)).check(matches(not(isEnabled())));
        } finally {
            LiveSessionUiRegistry.resetForTest();
        }
    }

    @Test
    public void privateStatusHierarchyContainsNoCameraOrRenderingSurface() {
        LiveSessionUiRegistry.resetForTest();
        try (ActivityScenario<LiveActivity> scenario =
                     ActivityScenario.launch(LiveActivity.class)) {
            scenario.onActivity(activity -> assertFalse(
                    containsMediaSurface(activity.findViewById(R.id.live_private_controls_root))));
        }
    }

    @Test
    public void productionStopButtonRequestsSafeStopOnceAndWaitsForTerminalUpdate() {
        AtomicInteger stopRequests = new AtomicInteger();
        LiveSessionUiRegistry.activate(SessionState.LIVE, stopRequests::incrementAndGet);
        try (ActivityScenario<LiveActivity> scenario = ActivityScenario.launch(LiveActivity.class)) {
            onView(withId(R.id.live_status_label))
                    .check(matches(withText(R.string.live_status_healthy_label)));

            onView(withId(R.id.stop_live_session)).perform(click());
            onView(withId(R.id.stop_live_session)).perform(click());

            assertEquals(1, stopRequests.get());
            onView(withId(R.id.live_status_label))
                    .check(matches(withText(R.string.live_status_healthy_label)));

            LiveSessionUiRegistry.update(SessionState.ENDED);
            onView(withId(R.id.live_status_label))
                    .check(matches(withText(R.string.live_status_stopped_label)));
            onView(withId(R.id.stop_live_session)).check(matches(not(isEnabled())));
        } finally {
            LiveSessionUiRegistry.resetForTest();
        }
    }

    @Test
    public void typedPublisherHealthIsHonestPrivateAndPayloadFree() {
        LiveSessionUiRegistry.resetForTest();
        LiveSessionUiRegistry.activate(SessionState.LIVE, () -> { });
        try (ActivityScenario<LiveActivity> scenario = ActivityScenario.launch(LiveActivity.class)) {
            LiveSessionUiRegistry.updatePublisherHealth(
                    new LiveSessionCoordinator.PublisherHealth(
                            LiveSessionCoordinator.PublisherState.RECONNECTING,
                            LiveSessionCoordinator.PublisherFailure.NETWORK, 0, 0L, false));
            onView(withId(R.id.live_publisher_status))
                    .check(matches(withText(R.string.live_publisher_reconnecting)));

            LiveSessionUiRegistry.updatePublisherHealth(
                    new LiveSessionCoordinator.PublisherHealth(
                            LiveSessionCoordinator.PublisherState.FAILED,
                            LiveSessionCoordinator.PublisherFailure.AUTHENTICATION,
                            0, 0L, false));
            onView(withId(R.id.live_publisher_status))
                    .check(matches(withText(R.string.live_publisher_auth_failed)));
            scenario.onActivity(activity -> {
                String status = ((android.widget.TextView) activity.findViewById(
                        R.id.live_publisher_status)).getText().toString();
                assertFalse(status.contains("rtmp"));
                assertFalse(status.contains("secret"));
                assertTrue(status.contains("stopped"));
            });
        } finally {
            LiveSessionUiRegistry.resetForTest();
        }
    }

    private static boolean containsMediaSurface(View view) {
        if (view instanceof SurfaceView
                || view instanceof TextureView
                || view instanceof PreviewView) {
            return true;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsMediaSurface(group.getChildAt(index))) {
                    return true;
                }
            }
        }
        return false;
    }
}
