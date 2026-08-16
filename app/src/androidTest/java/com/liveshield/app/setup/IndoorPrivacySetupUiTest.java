package com.liveshield.app.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.liveshield.app.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Private setup controls; no camera, media, destination, or recognized payload enters this test. */
@RunWith(AndroidJUnit4.class)
public final class IndoorPrivacySetupUiTest {
    @Test
    public void privateWordsAreSessionOnlyAndUpdateConfiguration() {
        try (ActivityScenario<SetupActivity> scenario =
                     ActivityScenario.launch(SetupActivity.class)) {
            scenario.onActivity(activity -> {
                activity.onScopeDisclosureAccepted();
                activity.findViewById(R.id.toggle_watchlist_details).performClick();
                EditText input = activity.findViewById(R.id.watchlist_term_input);
                input.getText().append("  Private Brand  ");
                activity.findViewById(R.id.add_watchlist_term).performClick();
                assertEquals(1, activity.sessionPrivacyConfiguration()
                        .normalizedWatchlistTerms().size());
                assertTrue(activity.sessionPrivacyConfiguration()
                        .normalizedWatchlistTerms().contains("PRIVATE BRAND"));
                assertFalse(input.isSaveEnabled());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue(activity.sessionPrivacyConfiguration()
                        .normalizedWatchlistTerms().isEmpty());
            });
        }
    }

    @Test
    public void qrProtectionRowDefaultsOnAndUpdatesSessionConfiguration() {
        try (ActivityScenario<SetupActivity> scenario =
                     ActivityScenario.launch(SetupActivity.class)) {
            scenario.onActivity(activity -> {
                CheckBox toggle = activity.findViewById(R.id.cover_qr_codes_toggle);
                assertNotNull(toggle);
                assertTrue(toggle.isChecked());
                assertTrue(activity.sessionPrivacyConfiguration()
                        .automaticBarcodeProtectionEnabled());

                toggle.performClick();

                assertFalse(toggle.isChecked());
                assertFalse(activity.sessionPrivacyConfiguration()
                        .automaticBarcodeProtectionEnabled());
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
