package com.videofabrikasi.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public class MainActivityTest {
    @Rule public ActivityScenarioRule<MainActivity> rule = new ActivityScenarioRule<>(MainActivity.class);

    @Test public void criticalControlsAreVisibleAndIdeaEditable() {
        onView(withId(R.id.username)).check(matches(isDisplayed()));
        onView(withId(R.id.token)).check(matches(isDisplayed()));
        onView(withId(R.id.idea)).check(matches(isDisplayed()));
        onView(withId(R.id.generate)).check(matches(isDisplayed()));
        onView(withId(R.id.prev_project)).check(matches(isDisplayed()));
        onView(withId(R.id.next_project)).check(matches(isDisplayed()));
        onView(withId(R.id.refresh)).check(matches(isDisplayed()));
        onView(withId(R.id.retry)).check(matches(isDisplayed()));
        onView(withId(R.id.download)).check(matches(isDisplayed()));
        onView(withId(R.id.play_pause)).check(matches(isDisplayed()));
        String sample="Bu test için yeterince uzun örnek bir video hikâyesidir.";
        onView(withId(R.id.idea)).perform(replaceText(sample), closeSoftKeyboard());
        onView(withId(R.id.idea)).check(matches(withText(sample)));
    }

    @Test public void generateRejectsMissingCredentialsWithoutCrash() {
        onView(withId(R.id.username)).perform(replaceText(""), closeSoftKeyboard());
        onView(withId(R.id.token)).perform(replaceText(""), closeSoftKeyboard());
        onView(withId(R.id.generate)).perform(click());
        onView(withId(R.id.generate)).check(matches(isDisplayed()));
    }

    @Test public void playRejectsMissingVerifiedVideoWithoutCrash() {
        onView(withId(R.id.play_pause)).perform(click());
        onView(withId(R.id.play_pause)).check(matches(isDisplayed()));
    }

    @Test public void aiOutputMustBeExplicitlySuccessfulOnAndroid() throws Exception {
        assertEquals("AI TAMAMLANDI", KaggleClient.outputStateFromJson(
                "{\"stage\":\"COMPLETE\",\"ai_ok\":true,\"final\":\"FINAL.mp4\"}"));
    }

    @Test public void fallbackIsNeverReportedAsAiSuccessOnAndroid() throws Exception {
        String state = KaggleClient.outputStateFromJson(
                "{\"stage\":\"COMPLETE_FALLBACK\",\"ai_ok\":false,\"error\":\"model failed\"}");
        assertTrue(state.startsWith("AI BAŞARISIZ — FALLBACK"));
        assertNotEquals("AI TAMAMLANDI", state);
    }

    @Test public void unrelatedDownloadCompletionBroadcastDoesNotCrashActivity() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        intent.setPackage(context.getPackageName());
        intent.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 987654321L);
        context.sendBroadcast(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withId(R.id.generate)).check(matches(isDisplayed()));
    }
}
