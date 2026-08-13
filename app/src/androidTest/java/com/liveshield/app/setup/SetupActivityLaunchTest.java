package com.liveshield.app.setup;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.widget.Button;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;
import com.liveshield.app.R;
import com.liveshield.app.session.CameraSessionGraph;
import com.liveshield.video.camera.CameraSessionController;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SetupActivityLaunchTest {
    private static final long READY_TIMEOUT_MILLIS = 15_000L;
    @Rule
    public final GrantPermissionRule cameraPermission =
            GrantPermissionRule.grant(Manifest.permission.CAMERA);

    @Test
    public void launchAttachesProductionCoordinatorButStartRemainsFailClosed() throws Exception {
        try (ActivityScenario<SetupActivity> scenario =
                     ActivityScenario.launch(SetupActivity.class)) {
            onView(withId(R.id.acknowledge_scope_disclosure)).perform(click());
            AtomicBoolean attached = new AtomicBoolean();
            AtomicBoolean startEnabled = new AtomicBoolean(true);
            AtomicReference<CameraSessionGraph.ReadinessSnapshot> snapshot =
                    new AtomicReference<>();
            long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline && !fullyReady(snapshot.get())) {
                scenario.onActivity(activity -> {
                    attached.set(activity.hasSessionCoordinatorForTest());
                    snapshot.set(activity.readinessSnapshotForTest());
                    Button start = activity.findViewById(R.id.start_protected_live);
                    startEnabled.set(start.isEnabled());
                });
                assertFalse(startEnabled.get());
                Thread.sleep(100L);
            }

            assertTrue(attached.get());
            CameraSessionGraph.ReadinessSnapshot result = snapshot.get();
            assertTrue("Camera graph did not bind: " + result,
                    result != null
                            && result.cameraState() == CameraSessionController.State.BOUND);
            assertTrue("Camera transform metadata never became valid: " + result,
                    result.transformReady());
            assertTrue("Sanitized renderer never swapped a frame: " + result,
                    result.rendererReadiness() == PrivacySurfaceProcessor.Readiness.READY);
            assertTrue("Sanitized encoder never emitted AVC configuration: " + result,
                    result.encoderReady());
            assertFalse(startEnabled.get());
        }
    }

    private static boolean fullyReady(CameraSessionGraph.ReadinessSnapshot snapshot) {
        return snapshot != null
                && snapshot.cameraState() == CameraSessionController.State.BOUND
                && snapshot.transformReady()
                && snapshot.rendererReadiness() == PrivacySurfaceProcessor.Readiness.READY
                && snapshot.encoderReady();
    }
}
