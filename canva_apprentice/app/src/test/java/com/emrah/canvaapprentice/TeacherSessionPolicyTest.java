package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class TeacherSessionPolicyTest {
    @Test public void runningMatchingSessionIsAccepted() {
        assertTrue(TeacherSessionPolicy.isCurrent("s1", "s1", TaskState.Mode.RUNNING));
    }

    @Test public void oldSessionIsRejectedAfterNewTaskOrResume() {
        assertFalse(TeacherSessionPolicy.isCurrent("s1", "s2", TaskState.Mode.RUNNING));
    }

    @Test public void stoppedOrHumanTakeoverRejectsEvenMatchingSession() {
        assertFalse(TeacherSessionPolicy.isCurrent("s1", "s1", TaskState.Mode.STOPPED));
        assertFalse(TeacherSessionPolicy.isCurrent("s1", "s1", TaskState.Mode.HUMAN_TAKEOVER));
    }

    @Test public void emptySessionFailsClosed() {
        assertFalse(TeacherSessionPolicy.isCurrent("", "", TaskState.Mode.RUNNING));
    }

    @Test public void whitespaceOrControlContaminatedSessionFailsClosedEvenWhenBothSidesMatch() {
        assertFalse(TeacherSessionPolicy.isCurrent(" session-a", " session-a", TaskState.Mode.RUNNING));
        assertFalse(TeacherSessionPolicy.isCurrent("session-a ", "session-a ", TaskState.Mode.RUNNING));
        assertFalse(TeacherSessionPolicy.isCurrent("session\ta", "session\ta", TaskState.Mode.RUNNING));
        assertFalse(TeacherSessionPolicy.isCurrent("session\na", "session\na", TaskState.Mode.RUNNING));
        assertFalse(TeacherSessionPolicy.isCurrent("session\u0000a", "session\u0000a", TaskState.Mode.RUNNING));
    }

    @Test public void cleanSessionDoesNotMatchContaminatedVariant() {
        assertFalse(TeacherSessionPolicy.isCurrent("session-a", "session-a ", TaskState.Mode.RUNNING));
        assertFalse(TeacherSessionPolicy.isCurrent("session-a\t", "session-a", TaskState.Mode.RUNNING));
    }
}
