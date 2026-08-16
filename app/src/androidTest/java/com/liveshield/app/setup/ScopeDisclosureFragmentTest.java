package com.liveshield.app.setup;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.liveshield.app.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Entry-gate coverage that never starts the production camera/session graph. */
@RunWith(AndroidJUnit4.class)
public final class ScopeDisclosureFragmentTest {
    @Test
    public void explicitAcknowledgementIsRequiredBeforeSetupBecomesAvailable() {
        try (ActivityScenario<SetupActivity> scenario =
                     ActivityScenario.launch(SetupActivity.class)) {
            scenario.onActivity(activity -> {
                assertFalse(activity.isScopeDisclosureAcceptedForTest());
                assertFalse(activity.hasSessionCoordinatorForTest());
                assertTrue(activity.findViewById(R.id.setup_content).getVisibility() == View.GONE);
                SetupActivityTestHarness.installCameraPermission(activity, false);
            });

            onView(withId(R.id.scope_disclosure_title)).check(matches(isDisplayed()));
            onView(withId(R.id.scope_disclosure_visual_only)).check(matches(isDisplayed()));
            onView(withId(R.id.scope_disclosure_unsupported)).check(matches(isDisplayed()));
            onView(withId(R.id.scope_disclosure_review)).check(matches(isDisplayed()));
            onView(withId(R.id.setup_content)).check(matches(not(isDisplayed())));

            onView(withId(R.id.acknowledge_scope_disclosure)).perform(click());
            scenario.onActivity(activity ->
                    SetupActivityTestHarness.install(activity, SetupUiListener.NO_OP));

            onView(withId(R.id.setup_content)).check(matches(isDisplayed()));
            onView(withId(R.id.scope_disclosure_container)).check(matches(not(isDisplayed())));
            scenario.onActivity(activity -> {
                assertTrue(activity.isScopeDisclosureAcceptedForTest());
                assertFalse(activity.hasSessionCoordinatorForTest());
            });
        }
    }
}
