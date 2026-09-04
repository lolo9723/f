package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class TeacherRequestEpochTest {
    @Test public void invalidateRejectsOlderTeacherCallback() {
        TeacherRequestEpoch guard = new TeacherRequestEpoch();
        long old = guard.capture();
        guard.invalidate();
        assertFalse(guard.isCurrent(old));
        assertTrue(guard.isCurrent(guard.capture()));
    }

    @Test public void repeatedLifecycleBoundariesKeepAllOlderCallbacksStale() {
        TeacherRequestEpoch guard = new TeacherRequestEpoch();
        long taskOne = guard.capture();
        guard.invalidate(); // STOP or HUMAN takeover
        long taskTwo = guard.capture();
        guard.invalidate(); // new task or DEVAM ET
        long resumed = guard.capture();

        assertFalse(guard.isCurrent(taskOne));
        assertFalse(guard.isCurrent(taskTwo));
        assertTrue(guard.isCurrent(resumed));
    }
}
