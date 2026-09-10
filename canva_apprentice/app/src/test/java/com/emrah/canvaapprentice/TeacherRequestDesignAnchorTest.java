package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TeacherRequestDesignAnchorTest {
    @Test
    public void sameSessionTokenAndAnchorRemainCurrent() {
        assertTrue(TeacherRequestPolicy.isCurrent(
                "session-1", "session-1", TaskState.Mode.RUNNING,
                "request-1", "request-1", "design-A", "design-A"));
    }

    @Test
    public void anchorDriftInvalidatesOtherwiseCurrentTeacherRequest() {
        assertFalse(TeacherRequestPolicy.isCurrent(
                "session-1", "session-1", TaskState.Mode.RUNNING,
                "request-1", "request-1", "design-A", "design-B"));
    }

    @Test
    public void emptyAnchorCanOnlyRemainCurrentWhileItStaysEmpty() {
        assertTrue(TeacherRequestPolicy.isCurrent(
                "session-1", "session-1", TaskState.Mode.RUNNING,
                "request-1", "request-1", "", ""));
        assertFalse(TeacherRequestPolicy.isCurrent(
                "session-1", "session-1", TaskState.Mode.RUNNING,
                "request-1", "request-1", "", "design-A"));
    }

    @Test
    public void nullAnchorFailsClosed() {
        assertFalse(TeacherRequestPolicy.isCurrent(
                "session-1", "session-1", TaskState.Mode.RUNNING,
                "request-1", "request-1", null, "design-A"));
        assertFalse(TeacherRequestPolicy.isCurrent(
                "session-1", "session-1", TaskState.Mode.RUNNING,
                "request-1", "request-1", "design-A", null));
    }
}
