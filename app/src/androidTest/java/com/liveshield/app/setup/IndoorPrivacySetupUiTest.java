package com.liveshield.app.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.liveshield.app.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Private setup controls; no camera, media, destination, or recognized payload enters this test. */
@RunWith(AndroidJUnit4.class)
public final class IndoorPrivacySetupUiTest {
    @Test
    public void unsupportedPrivateWordControlsAreAbsentFromProductionSetup() {
        try (ActivityScenario<SetupActivity> scenario =
                     ActivityScenario.launch(SetupActivity.class)) {
            scenario.onActivity(activity -> {
                String packageName = activity.getPackageName();
                assertEquals(0, activity.getResources().getIdentifier(
                        "toggle_watchlist_details", "id", packageName));
                assertEquals(0, activity.getResources().getIdentifier(
                        "watchlist_term_input", "id", packageName));
                assertEquals(0, activity.getResources().getIdentifier(
                        "add_watchlist_term", "id", packageName));
                assertTrue(activity.sessionPrivacyConfiguration()
                        .normalizedWatchlistTerms().isEmpty());
            });
        }
    }

    @Test
    public void drawOnlyZoneEditorHasNoNumericEntryAndClearsOnRecreation() {
        try (ActivityScenario<SetupActivity> scenario =
                     ActivityScenario.launch(SetupActivity.class)) {
            scenario.onActivity(activity -> {
                SetupActivityTestHarness.installCameraPermission(activity, false);
                activity.onScopeDisclosureAccepted();
                SetupActivityTestHarness.install(activity, SetupUiListener.NO_OP);
                PrivacyZoneEditorView overlay = activity.findViewById(
                        R.id.privacy_zone_editor_overlay);
                assertTrue(overlay.isFocusable());
                assertNotNull(overlay.getContentDescription());
                assertNotNull(activity.findViewById(R.id.toggle_zone_drawing));
                assertEquals(View.GONE, overlay.getVisibility());
                activity.findViewById(R.id.toggle_zone_drawing).performClick();
                assertEquals(View.VISIBLE, overlay.getVisibility());
                assertTrue(activity.sessionPrivacyConfiguration().activePrivacyZones().isEmpty());
            });
            scenario.recreate();
            scenario.onActivity(activity ->
                    assertTrue(activity.sessionPrivacyConfiguration().activePrivacyZones().isEmpty()));
        }
    }
}
