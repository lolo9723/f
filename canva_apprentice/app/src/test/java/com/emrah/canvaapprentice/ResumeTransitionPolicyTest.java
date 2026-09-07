package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class ResumeTransitionPolicyTest {
    @Test public void onlyHumanTakeoverMayResume() {
        assertTrue(ResumeTransitionPolicy.mayResume(TaskState.Mode.HUMAN_TAKEOVER));
        assertFalse(ResumeTransitionPolicy.mayResume(TaskState.Mode.IDLE));
        assertFalse(ResumeTransitionPolicy.mayResume(TaskState.Mode.RUNNING));
        assertFalse(ResumeTransitionPolicy.mayResume(TaskState.Mode.STOPPED));
    }
}
