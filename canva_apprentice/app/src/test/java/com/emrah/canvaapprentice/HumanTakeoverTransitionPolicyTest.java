package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HumanTakeoverTransitionPolicyTest {
    @Test public void onlyRunningTaskMayEnterHumanTakeover() {
        assertTrue(HumanTakeoverTransitionPolicy.mayPause(TaskState.Mode.RUNNING));
        assertFalse(HumanTakeoverTransitionPolicy.mayPause(TaskState.Mode.IDLE));
        assertFalse(HumanTakeoverTransitionPolicy.mayPause(TaskState.Mode.HUMAN_TAKEOVER));
        assertFalse(HumanTakeoverTransitionPolicy.mayPause(TaskState.Mode.STOPPED));
    }
}
