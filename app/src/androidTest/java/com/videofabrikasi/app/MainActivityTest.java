package com.videofabrikasi.app;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
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
        onView(withId(R.id.refresh)).check(matches(isDisplayed()));
        onView(withId(R.id.retry)).check(matches(isDisplayed()));
        onView(withId(R.id.download)).check(matches(isDisplayed()));
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
}
