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

    @Test public void rejectsMatchingButNonCanonicalRequestTokens() {
        String[] malformed = {
                "req 2",
                "req\t2",
                "req\n2",
                "req\u00a02",
                "req\u200b2"
        };
        for (String token : malformed) {
            assertFalse("must fail closed for malformed token: " + token,
                    TeacherRequestPolicy.isCurrent(
                            "session-a", "session-a", TaskState.Mode.RUNNING, token, token));
        }
    }

    @Test public void rejectsWhenOnlyOneRequestTokenIsNonCanonical() {
        assertFalse(TeacherRequestPolicy.isCurrent(
                "session-a", "session-a", TaskState.Mode.RUNNING, "req-2", "req-2\u200b"));
        assertFalse(TeacherRequestPolicy.isCurrent(
                "session-a", "session-a", TaskState.Mode.RUNNING, "req-2\u00a0", "req-2"));
    }
}
