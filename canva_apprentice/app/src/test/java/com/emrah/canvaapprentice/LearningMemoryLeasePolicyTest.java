package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class LearningMemoryLeasePolicyTest {
    @After public void cleanup() {
        TeacherExecutionLease.invalidateGlobal();
    }

    @Test public void currentTeacherActionMayRecord() {
        String token = TeacherExecutionLease.beginGlobal();
        AgentAction action = new AgentAction(
                AgentAction.Type.CLICK_TEXT, "Open", "", 0.99, "test", false, token);

        assertTrue(LearningMemoryLeasePolicy.canRecord(action));
    }

    @Test public void staleTeacherActionCannotRecordAfterNewRequest() {
        String oldToken = TeacherExecutionLease.beginGlobal();
        AgentAction stale = new AgentAction(
                AgentAction.Type.CLICK_TEXT, "Open", "", 0.99, "old", false, oldToken);

        TeacherExecutionLease.beginGlobal();

        assertFalse(LearningMemoryLeasePolicy.canRecord(stale));
    }

    @Test public void unleasedInternalActionRemainsRecordable() {
        AgentAction internal = new AgentAction(
                AgentAction.Type.BACK, "", "", 0.99, "internal", false, "");

        assertTrue(LearningMemoryLeasePolicy.canRecord(internal));
    }
}
