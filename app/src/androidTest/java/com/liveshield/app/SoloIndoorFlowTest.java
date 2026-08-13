package com.liveshield.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.liveshield.app.session.LiveActivity;
import com.liveshield.app.setup.SelectableFace;
import com.liveshield.app.setup.SetupActivity;
import com.liveshield.app.setup.SetupActivityTestHarness;
import com.liveshield.app.setup.SetupUiListener;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.session.SessionState;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Espresso acceptance coverage for the bounded, silent, solo-indoor creator flow. */
@RunWith(AndroidJUnit4.class)
public final class SoloIndoorFlowTest {
    private static final long SELECTED_TRACK_ID = 41;

    @Test
    public void a01DeniedCameraPermissionKeepsSetupExplicitlyFailClosed() {
        try (ActivityScenario<SetupActivity> scenario =
                     ActivityScenario.launch(SetupActivity.class)) {
            scenario.onActivity(activity ->
                    SetupActivityTestHarness.installCameraPermission(activity, false));
            onView(withText(R.string.scope_disclosure_unsupported)).check(matches(isDisplayed()));
            onView(withId(R.id.acknowledge_scope_disclosure)).perform(click());
            onView(withId(R.id.camera_permission_status))
                    .check(matches(withText(R.string.camera_permission_denied)));
            onView(withId(R.id.request_camera_permission)).check(matches(isDisplayed()));
            onView(withId(R.id.privacy_readiness_status))
                    .check(matches(withText(R.string.privacy_status_permission_required)));
            onView(withId(R.id.start_protected_live)).check(matches(not(isEnabled())));
            onView(withText(R.string.video_only_notice)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void a02SetupProgressionRequiresPermissionFreshHostAndProtectionReadiness() {
        AtomicBoolean startRequested = new AtomicBoolean();
        Intent launchIntent = new Intent(applicationContext(), SetupActivity.class)
                .putExtra("installUiContractHarnessForTest", true);

        try (ActivityScenario<SetupActivity> scenario =
                     ActivityScenario.launch(launchIntent)) {
            scenario.onActivity(SetupActivityTestHarness::assertReleaseBoundary);
            scenario.onActivity(activity ->
                    SetupActivityTestHarness.installCameraPermission(activity, true));
            onView(withText(R.string.scope_disclosure_unsupported)).check(matches(isDisplayed()));
            onView(withId(R.id.acknowledge_scope_disclosure)).perform(click());
            scenario.onActivity(activity -> {
                SetupActivityTestHarness.install(activity, new SetupUiListener() {
                    @Override
                    public void onHostSelectionRequested(long trackId) {
                    }

                    @Override
                    public void onStartRequested() {
                        startRequested.set(true);
                    }
                });
                assertTrue(SetupActivityTestHarness.hasDetachedProductionGraph(activity));
                activity.findViewById(R.id.configure_stream_destination).performClick();
            });
            onView(withId(R.id.camera_permission_status))
                    .check(matches(withText(R.string.camera_permission_granted)));
            onView(withId(R.id.start_protected_live)).check(matches(not(isEnabled())));

            scenario.onActivity(activity -> activity.showSelectableFaces(
                    List.of(new SelectableFace(
                            SELECTED_TRACK_ID,
                            new NormalizedRect(0.2, 0.2, 0.6, 0.7),
                            true)),
                    SELECTED_TRACK_ID));
            onView(withId(R.id.privacy_readiness_status))
                    .check(matches(withText(R.string.privacy_status_checking)));
            onView(withId(R.id.start_protected_live)).check(matches(not(isEnabled())));

            scenario.onActivity(activity -> {
                activity.showPrivacyReady(true);
                activity.findViewById(R.id.start_protected_live).performClick();
            });
            onView(withId(R.id.privacy_readiness_status))
                    .check(matches(withText(R.string.privacy_status_ready)));
            onView(withId(R.id.start_protected_live)).check(matches(isEnabled()));
            assertTrue(startRequested.get());
            scenario.onActivity(activity -> assertTrue(
                    "Late production bootstrap must remain detached from the UI-contract test",
                    SetupActivityTestHarness.hasDetachedProductionGraph(activity)));
        }
    }

    @Test
    public void a03HealthStatesUseHonestComprehensibleLabelsAndDetails() {
        try (ActivityScenario<LiveActivity> scenario =
                     ActivityScenario.launch(LiveActivity.class)) {
            scenario.onActivity(activity -> activity.showSessionState(SessionState.LIVE));
            onView(withId(R.id.live_status_label))
                    .check(matches(withText(R.string.live_status_healthy_label)));
            onView(withId(R.id.live_status_detail))
                    .check(matches(withText(R.string.live_status_healthy_detail)));

            scenario.onActivity(activity -> activity.showSessionState(SessionState.DEGRADED));
            onView(withId(R.id.live_status_label))
                    .check(matches(withText(R.string.live_status_degraded_label)));
            onView(withId(R.id.live_status_detail))
                    .check(matches(withText(R.string.live_status_degraded_detail)));

            scenario.onActivity(activity -> activity.showSessionState(SessionState.SHIELDING));
            onView(withId(R.id.live_status_label))
                    .check(matches(withText(R.string.live_status_shielding_label)));
            onView(withId(R.id.live_status_detail))
                    .check(matches(withText(R.string.live_status_shielding_detail)));
            onView(withText(R.string.privacy_limit_notice)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void a04ManifestAndRuntimeCannotAuthorizeMicrophoneCapture() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_PERMISSIONS);
        List<String> requested = info.requestedPermissions == null
                ? List.of() : Arrays.asList(info.requestedPermissions);

        assertFalse(requested.contains(Manifest.permission.RECORD_AUDIO));
        assertFalse(requested.contains("android.permission.CAPTURE_AUDIO_OUTPUT"));
        assertEquals(PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
        try (ActivityScenario<SetupActivity> scenario =
                     ActivityScenario.launch(SetupActivity.class)) {
            scenario.onActivity(activity ->
                    SetupActivityTestHarness.installCameraPermission(activity, false));
            onView(withText(R.string.scope_disclosure_visual_only)).check(matches(isDisplayed()));
            onView(withId(R.id.acknowledge_scope_disclosure)).perform(click());
            onView(withText(R.string.video_only_notice)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void a05EndSessionAcknowledgementStopsUiAndCannotBeRepeated() {
        AtomicInteger stopRequests = new AtomicInteger();

        try (ActivityScenario<LiveActivity> scenario =
                     ActivityScenario.launch(LiveActivity.class)) {
            scenario.onActivity(activity -> {
                activity.showSessionState(SessionState.LIVE);
                activity.setLiveUiListener(() -> {
                    stopRequests.incrementAndGet();
                    activity.showSessionState(SessionState.ENDED);
                });
            });
            onView(withId(R.id.stop_live_session)).check(matches(isEnabled()));
            onView(withId(R.id.stop_live_session)).perform(click());

            assertEquals(1, stopRequests.get());
            onView(withId(R.id.live_status_label))
                    .check(matches(withText(R.string.live_status_stopped_label)));
            onView(withId(R.id.live_status_detail))
                    .check(matches(withText(R.string.live_status_stopped_detail)));
            onView(withId(R.id.stop_live_session)).check(matches(not(isEnabled())));
        }
    }

    private static Context applicationContext() {
        return ApplicationProvider.getApplicationContext();
    }

}
