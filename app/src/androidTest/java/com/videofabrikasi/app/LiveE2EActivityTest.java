package com.videofabrikasi.app;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
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

@RunWith(AndroidJUnit4.class)
public class LiveE2EActivityTest {
    @Rule public ActivityScenarioRule<LiveE2EActivity> rule =
            new ActivityScenarioRule<>(LiveE2EActivity.class);

    @Test public void easyKaggleConnectionControlsAreVisible() {
        onView(withId(R.id.e2e_connect_kaggle)).check(matches(isDisplayed()));
        onView(withId(R.id.e2e_import_token_file)).check(matches(isDisplayed()));
        onView(withId(R.id.e2e_import_clipboard)).check(matches(isDisplayed()));
    }

    @Test public void liveCertificationUiIsVisibleAndMissingCredentialsDoNotCrash() {
        onView(withId(R.id.e2e_username)).check(matches(isDisplayed()))
                .perform(replaceText(""), closeSoftKeyboard());
        onView(withId(R.id.e2e_token)).check(matches(isDisplayed()))
                .perform(replaceText(""), closeSoftKeyboard());
        onView(withId(R.id.e2e_start)).check(matches(isDisplayed())).perform(click());
        onView(withId(R.id.e2e_status)).check(matches(isDisplayed()));
        onView(withId(R.id.e2e_start)).check(matches(isDisplayed()));
    }

    @Test public void canonicalStatusJsonParsesAndPassesOnRealAndroidJson() throws Exception {
        String json = "{"
                + "\"stage\":\"COMPLETE\","
                + "\"ai_ok\":true,"
                + "\"engine\":\"LTX-Video 2B distilled 0.9.6 T4-FP16 story-v4\","
                + "\"scenes\":5,"
                + "\"prompt_language\":\"English\","
                + "\"translation\":{\"mode\":\"tr_to_en\"},"
                + "\"continuity\":\"previous_scene_last_frame\","
                + "\"continuity_strength\":0.65,"
                + "\"audio\":\"procedural_generic_emotion_sfx_aac\","
                + "\"quality_gate\":\"siglip_semantic_plus_visual_integrity\","
                + "\"quality\":[{\"pass\":true},{\"pass\":true},{\"pass\":true},{\"pass\":true},{\"pass\":true}],"
                + "\"final\":\"FINAL.mp4\"} ";
        LiveE2ECertificate c = LiveE2ECertificate.parse(json);
        assertTrue(c.passesCanonicalV4());
        assertTrue(c.summary().contains("story-v4"));
        assertTrue(c.summary().contains("tr_to_en"));
    }

    @Test public void androidFinalTrackContractRequiresH264AndAac() {
        assertTrue(LiveE2EActivity.hasCanonicalH264AacTracks(
                new String[]{"video/avc", "audio/mp4a-latm"}));
        assertFalse(LiveE2EActivity.hasCanonicalH264AacTracks(
                new String[]{"video/hevc", "audio/mp4a-latm"}));
        assertFalse(LiveE2EActivity.hasCanonicalH264AacTracks(
                new String[]{"video/avc", "audio/mpeg"}));
        assertFalse(LiveE2EActivity.hasCanonicalH264AacTracks(
                new String[]{"video/avc"}));
    }

    @Test public void fallbackStatusJsonIsRejectedOnRealAndroidJson() throws Exception {
        LiveE2ECertificate c = LiveE2ECertificate.parse(
                "{\"stage\":\"COMPLETE_FALLBACK\",\"ai_ok\":false,"
                        + "\"engine\":\"fallback renderer\",\"error\":\"LTX failed\"}");
        assertFalse(c.passesCanonicalV4());
        assertTrue(c.failureReason().contains("ai_ok=false"));
    }
}
