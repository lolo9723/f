package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimeRestoreContinuityPolicyTest {

    @Test public void runningProcessRestoreInvalidatesPersistedContinuity() {
        assertTrue(RuntimeRestoreContinuityPolicy.mustInvalidate(TaskState.Mode.RUNNING));
    }

    @Test public void humanTakeoverProcessRestoreAlsoInvalidatesPersistedContinuity() {
        assertTrue(RuntimeRestoreContinuityPolicy.mustInvalidate(TaskState.Mode.HUMAN_TAKEOVER));
    }

    @Test public void idleAndStoppedStateCannotAuthorizeRuntimeContinuityAnyway() {
        assertFalse(RuntimeRestoreContinuityPolicy.mustInvalidate(TaskState.Mode.IDLE));
        assertFalse(RuntimeRestoreContinuityPolicy.mustInvalidate(TaskState.Mode.STOPPED));
    }
}
