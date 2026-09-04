package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TeacherRequestPolicyTest {
    @Test public void acceptsOnlyMatchingActiveRequestInRunningSession() {
        assertTrue(TeacherRequestPolicy.isCurrent(
                "session-a", "session-a", TaskState.Mode.RUNNING, "req-2", "req-2"));
        assertFalse(TeacherRequestPolicy.isCurrent(
                "session-a", "session-a", TaskState.Mode.RUNNING, "req-1", "req-2"));
    }

    @Test public void rejectsStaleSessionEvenWhenRequestTokenMatches() {
        assertFalse(TeacherRequestPolicy.isCurrent(
                "session-old", "session-new", TaskState.Mode.RUNNING, "req-2", "req-2"));
    }

    @Test public void rejectsNonRunningModesAndEmptyTokens() {
        assertFalse(TeacherRequestPolicy.isCurrent(
                "session-a", "session-a", TaskState.Mode.HUMAN_TAKEOVER, "req-2", "req-2"));
        assertFalse(TeacherRequestPolicy.isCurrent(
                "session-a", "session-a", TaskState.Mode.RUNNING, "", ""));
    }
}
