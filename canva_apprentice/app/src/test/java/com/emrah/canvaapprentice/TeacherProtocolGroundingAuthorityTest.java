package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
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

    @Test public void parserRejectsLeaseWithoutImmutableTeacherAuthority() {
        String token = TeacherExecutionLease.beginGlobal();
        String marker = "CAA1_REPLY_ungrounded|";
        CheckpointRequestGuard.bind(marker, token);

        AgentAction action = TeacherProtocol.parse(
                marker + "CLICK_TEXT|Layers|0.99|safe",
                null
        );

        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals("", action.executionLeaseToken);
        assertEquals(token, CheckpointRequestGuard.currentBoundExecutionLease(marker));
        assertEquals(0, CheckpointRequestGuard.consumedSnapshotCountForTest());
    }

    @Test public void parserAcceptsFullyGroundedCurrentRequest() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin("grounded", "fp-current");
        assertTrue(authority.isValid());
        String token = authority.executionLeaseToken;

        AgentAction action = TeacherProtocol.parse(
                authority.marker + "CLICK_TEXT|Layers|0.99|safe",
                authority
        );

        assertEquals(AgentAction.Type.CLICK_TEXT, action.type);
        assertEquals(token, action.executionLeaseToken);
        assertEquals("Layers", action.target);
        assertEquals(1, CheckpointRequestGuard.consumedSnapshotCountForTest());
    }

    @Test public void staleFullyGroundedRequestLosesExecutionAuthorityAtParser() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin("stalegrounded", "fp-old");
        assertTrue(authority.isValid());
        CheckpointRequestGuard.onCheckpointCommitted();

        AgentAction action = TeacherProtocol.parse(
                authority.marker + "CLICK_TEXT|Layers|0.99|safe",
                authority
        );

        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals("", action.executionLeaseToken);
        assertEquals(0, CheckpointRequestGuard.consumedSnapshotCountForTest());
    }
}
