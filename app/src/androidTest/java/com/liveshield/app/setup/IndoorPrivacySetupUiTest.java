package com.liveshield.app.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.liveshield.app.R;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Private setup controls; no camera, media, destination, or recognized payload enters this test. */
@RunWith(AndroidJUnit4.class)
public final class IndoorPrivacySetupUiTest {
    @Test
    public void watchlistIsDisclosureGatedRemovableAndNeverRestored() {
        try (ActivityScenario<SetupActivity> scenario =
                     ActivityScenario.launch(SetupActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(View.GONE, activity.findViewById(R.id.setup_content).getVisibility());
                activity.onScopeDisclosureAccepted();
                SetupActivityTestHarness.install(activity, SetupUiListener.NO_OP);
                EditText input = activity.findViewById(R.id.watchlist_term_input);
                assertFalse(input.isSaveEnabled());
                setText(activity, R.id.watchlist_term_input, "Fictional Employer");
                activity.findViewById(R.id.add_watchlist_term).performClick();
                assertEquals(Set.of("fictional employer"),
                        activity.sessionPrivacyConfiguration().normalizedWatchlistTerms());
                LinearLayout rows = activity.findViewById(R.id.watchlist_terms_container);
                assertEquals(1, rows.getChildCount());
                LinearLayout row = (LinearLayout) rows.getChildAt(0);
                View remove = row.getChildAt(1);
                assertNotNull(remove.getContentDescription());
                remove.performClick();
                assertTrue(activity.sessionPrivacyConfiguration()
                        .normalizedWatchlistTerms().isEmpty());

                setText(activity, R.id.watchlist_term_input, "Session only");
                activity.findViewById(R.id.add_watchlist_term).performClick();
            });

            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue(activity.sessionPrivacyConfiguration()
                        .normalizedWatchlistTerms().isEmpty());
                assertEquals("", ((EditText) activity.findViewById(
                        R.id.watchlist_term_input)).getText().toString());
            });
        }
    }

    @Test
    public void drawOnlyZoneEditorHasNoNumericEntryAndClearsOnRecreation() {
        try (ActivityScenario<SetupActivity> scenario =
                     ActivityScenario.launch(SetupActivity.class)) {
            scenario.onActivity(activity -> {
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

    private static void setText(SetupActivity activity, int viewId, String value) {
        EditText input = activity.findViewById(viewId);
        assertFalse(input.isSaveEnabled());
        input.setText(value);
    }
}
