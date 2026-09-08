package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public final class VisualMutationRequiresBoundDesignTest {
    @After public void cleanup() {
        TeacherExecutionLease.invalidateGlobal();
    }

    @Test public void screenshotGroundedMutationFailsClosedWithoutBoundDesign() {
        String token = TeacherExecutionLease.beginGlobal();
        AgentAction action = new AgentAction(
                AgentAction.Type.TAP_NORM,
                "500,500",
                "",
                1.0,
                "visually grounded edit",
                true,
                token);
        TaskState state = new TaskState(
                "edit existing design",
                "",
                "   ",
                "",
                "",
                TaskState.Mode.RUNNING,
                false,
                0);

        SafetyGate.Decision decision = new SafetyGate().evaluate(
                action,
                state,
                AgentConstants.CANVA_PACKAGE);

        assertEquals(SafetyGate.Decision.Kind.BLOCK, decision.kind);
    }
}
