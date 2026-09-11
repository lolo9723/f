package com.emrah.canvaapprentice;

import org.junit.Test;

import static org.junit.Assert.*;

public final class AgentActionSnapshotAuthorityTest {
    private static AgentAction action(AgentAction.Type type) {
        return new AgentAction(type, "target", "value", 1.0, "test", false, "lease");
    }

    @Test public void exactAndFallbackStructuralTargetsRequireTeacherSnapshotAuthority() {
        assertTrue(action(AgentAction.Type.CLICK_NODE).requiresTeacherSnapshotAuthority());
        assertTrue(action(AgentAction.Type.SET_NODE_TEXT).requiresTeacherSnapshotAuthority());
        assertTrue(action(AgentAction.Type.CLICK_TEXT).requiresTeacherSnapshotAuthority());
        assertTrue(action(AgentAction.Type.SET_TEXT).requiresTeacherSnapshotAuthority());
    }

    @Test public void coordinateAndNavigationActionsDoNotBorrowStructuralSnapshotAuthority() {
        assertFalse(action(AgentAction.Type.TAP_NORM).requiresTeacherSnapshotAuthority());
        assertFalse(action(AgentAction.Type.DRAG_NORM).requiresTeacherSnapshotAuthority());
        assertFalse(action(AgentAction.Type.BACK).requiresTeacherSnapshotAuthority());
        assertFalse(action(AgentAction.Type.DONE).requiresTeacherSnapshotAuthority());
        assertFalse(action(AgentAction.Type.HUMAN_TAKEOVER).requiresTeacherSnapshotAuthority());
    }
}
