package com.emrah.canvaapprentice;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimeRestoreContinuityPolicyTest {

    @Test public void runningProcessRestoreInvalidatesPersistedContinuity() {
        assertTrue(RuntimeRestoreContinuityPolicy.mustInvalidate(TaskState.Mode.RUNNING));
    }

    @Test public void humanTakeoverProcessRestoreAlsoInvalidatesPersistedContinuity() {
        assertTrue(RuntimeRestoreContinuityPolicy.mustInvalidate(TaskState.Mode.HUMAN_TAKEOVER));
    }

    @Test public void idleAndStoppedRestoreStillInvalidatePersistedContinuity() {
        assertTrue(RuntimeRestoreContinuityPolicy.mustInvalidate(TaskState.Mode.IDLE));
        assertTrue(RuntimeRestoreContinuityPolicy.mustInvalidate(TaskState.Mode.STOPPED));
    }

    @Test public void unknownRestoreModeFailsClosed() {
        assertTrue(RuntimeRestoreContinuityPolicy.mustInvalidate(null));
    }
}
