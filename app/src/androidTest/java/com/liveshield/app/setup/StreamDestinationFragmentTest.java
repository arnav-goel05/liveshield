package com.liveshield.app.setup;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.swipeUp;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.liveshield.app.R;
import java.util.Arrays;
import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.runner.RunWith;

/** UI contract for explicit, private, session-only stream destination setup. */
@RunWith(AndroidJUnit4.class)
public final class StreamDestinationFragmentTest {
    @Test
    public void disclosureGatesDestinationAndDemoIsExplicitlyNotTikTok() {
        try (ActivityScenario<SetupActivity> scenario =
                     ActivityScenario.launch(SetupActivity.class)) {
            scenario.onActivity(activity ->
                    SetupActivityTestHarness.installCameraPermission(activity, false));
            onView(withId(R.id.destination_kind)).check(doesNotExist());
            onView(withText(R.string.scope_disclosure_visual_only)).check(matches(isDisplayed()));
            onView(withId(R.id.acknowledge_scope_disclosure)).perform(click());
            onView(withId(R.id.destination_section_header)).perform(scrollTo(), click());

            onView(withText(R.string.destination_local_demo))
                    .perform(scrollTo())
                    .check(matches(isDisplayed()));
            onView(withText(R.string.destination_video_only_explanation))
                    .check(matches(isDisplayed()));
            scenario.onActivity(activity ->
                    activity.findViewById(R.id.configure_stream_destination).performClick());
            scenario.onActivity(activity -> {
                assertTrue((activity.getWindow().getAttributes().flags
                        & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            });
        }
    }

    @Test
    public void externalSecretIsMaskedNotSavedAndClearedAfterSessionOnlyHandoff() {
        try (ActivityScenario<SetupActivity> scenario =
                     ActivityScenario.launch(SetupActivity.class)) {
            scenario.onActivity(activity ->
                    SetupActivityTestHarness.installCameraPermission(activity, false));
            onView(withId(R.id.acknowledge_scope_disclosure)).perform(click());
            onView(withId(R.id.destination_section_header)).perform(scrollTo(), click());
            onView(withId(R.id.destination_tiktok_external)).perform(scrollTo(), click());
            onView(withId(R.id.setup_content)).perform(swipeUp());
            onView(withText(R.string.destination_tiktok_eligibility))
                    .check(matches(isDisplayed()));
            onView(withId(R.id.external_stream_endpoint))
                    .perform(scrollTo(), replaceText("rtmps://live.example.invalid/app"));
            char[] secretCharacters = fictionalSecretCharacters();
            ViewAction secretEntry = new RedactedSecretEntryAction(secretCharacters);
            assertEquals("enter redacted session-only test secret", secretEntry.getDescription());
            assertEquals(secretEntry.getDescription(), secretEntry.toString());
            onView(withId(R.id.external_stream_secret))
                    .perform(scrollTo(), secretEntry);
            assertAllZero(secretCharacters);
            scenario.onActivity(activity -> {
                EditText secret = activity.findViewById(R.id.external_stream_secret);
                assertFalse(secret.isSaveEnabled());
                assertTrue((secret.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0);
            });
            onView(withId(R.id.configure_stream_destination)).perform(scrollTo());
            scenario.onActivity(activity ->
                    activity.findViewById(R.id.configure_stream_destination).performClick());
            onView(withId(R.id.external_stream_secret))
                    .perform(scrollTo())
                    .check(matches(withText("")));
            onView(withId(R.id.destination_private_status)).check(matches(
                    withText(R.string.destination_status_saved_session_only)));
            scenario.recreate();
            scenario.onActivity(activity -> assertNull(activity.streamDestinationForTest()));
            onView(withId(R.id.external_stream_secret))
                    .perform(scrollTo())
                    .check(matches(withText("")));
        }
    }

    private static char[] fictionalSecretCharacters() {
        return new char[]{
            'f', 'i', 'c', 't', 'i', 'o', 'n', 'a', 'l', '-',
            's', 'e', 's', 's', 'i', 'o', 'n', '-', 'k', 'e', 'y'
        };
    }

    private static void assertAllZero(char[] values) {
        for (char value : values) {
            assertEquals('\0', value);
        }
    }

    private static final class RedactedSecretEntryAction implements ViewAction {
        private static final String DESCRIPTION = "enter redacted session-only test secret";
        private final char[] source;

        private RedactedSecretEntryAction(char[] source) {
            this.source = source;
        }

        @Override
        public Matcher<View> getConstraints() {
            return androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(EditText.class);
        }

        @Override
        public String getDescription() {
            return DESCRIPTION;
        }

        @Override
        public String toString() {
            return DESCRIPTION;
        }

        @Override
        public void perform(UiController controller, View view) {
            char[] temporary = Arrays.copyOf(source, source.length);
            try {
                EditText editText = (EditText) view;
                editText.getText().replace(
                        0, editText.length(), new SecretCharacters(temporary));
                controller.loopMainThreadUntilIdle();
            } finally {
                Arrays.fill(temporary, '\0');
                Arrays.fill(source, '\0');
            }
        }
    }

    private static final class SecretCharacters implements CharSequence {
        private final char[] values;
        private final int offset;
        private final int size;

        private SecretCharacters(char[] values) {
            this(values, 0, values.length);
        }

        private SecretCharacters(char[] values, int offset, int size) {
            this.values = values;
            this.offset = offset;
            this.size = size;
        }

        @Override
        public int length() {
            return size;
        }

        @Override
        public char charAt(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Secret character index is outside the view");
            }
            return values[offset + index];
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            if (start < 0 || end < start || end > size) {
                throw new IndexOutOfBoundsException("Secret subsequence is outside the view");
            }
            return new SecretCharacters(values, offset + start, end - start);
        }

        @Override
        public String toString() {
            return "[redacted secret characters]";
        }
    }
}
