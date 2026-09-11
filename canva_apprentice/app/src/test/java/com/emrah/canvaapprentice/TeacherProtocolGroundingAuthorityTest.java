package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class TeacherProtocolGroundingAuthorityTest {
    @Before public void setUp() {
        CheckpointRequestGuard.resetForTest();
        TeacherExecutionLease.invalidateGlobal();
    }

    @After public void tearDown() {
        CheckpointRequestGuard.resetForTest();
        TeacherExecutionLease.invalidateGlobal();
    }

    @Test public void parserRejectsLeaseWithoutTeacherVisibleSnapshot() {
        String token = TeacherExecutionLease.beginGlobal();
        String marker = "CAA1_REPLY_ungrounded|";
        CheckpointRequestGuard.bind(marker, token);

        AgentAction action = TeacherProtocol.parse(
                marker + "CLICK_TEXT|Layers|0.99|safe",
                marker
        );

        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals("", action.executionLeaseToken);
        assertFalse(CheckpointRequestGuard.consume(marker).checkpointCurrent);
    }

    @Test public void parserAcceptsFullyGroundedCurrentRequest() {
        String token = TeacherExecutionLease.beginGlobal();
        String marker = "CAA1_REPLY_grounded|";
        CheckpointRequestGuard.bind(marker, token);
        assertTrue(CheckpointRequestGuard.bindSnapshot(marker, "fp-current"));

        AgentAction action = TeacherProtocol.parse(
                marker + "CLICK_TEXT|Layers|0.99|safe",
                marker
        );

        assertEquals(AgentAction.Type.CLICK_TEXT, action.type);
        assertEquals(token, action.executionLeaseToken);
        assertEquals("Layers", action.target);
        assertEquals(1, CheckpointRequestGuard.consumedSnapshotCountForTest());
    }

    @Test public void staleFullyGroundedRequestLosesExecutionAuthorityAtParser() {
        String token = TeacherExecutionLease.beginGlobal();
        String marker = "CAA1_REPLY_stalegrounded|";
        CheckpointRequestGuard.bind(marker, token);
        assertTrue(CheckpointRequestGuard.bindSnapshot(marker, "fp-old"));
        CheckpointRequestGuard.onCheckpointCommitted();

        AgentAction action = TeacherProtocol.parse(
                marker + "CLICK_TEXT|Layers|0.99|safe",
                marker
        );

        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals("", action.executionLeaseToken);
        assertEquals(0, CheckpointRequestGuard.consumedSnapshotCountForTest());
    }
}
