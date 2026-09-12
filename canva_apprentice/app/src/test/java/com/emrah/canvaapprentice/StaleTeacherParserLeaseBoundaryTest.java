package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public final class StaleTeacherParserLeaseBoundaryTest {
    @Before public void setUp() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @Test public void olderFullyGroundedReplyCannotCrossNewerExecutionLease() {
        TeacherRequestAuthority older = TeacherRequestAuthority.begin("olderReply", "snapshot-old");
        assertTrue(older.isValid());
        assertTrue(older.stillOwnsTransport());

        TeacherRequestAuthority newer = TeacherRequestAuthority.begin("newerReply", "snapshot-new");
        assertTrue(newer.isValid());
        assertTrue(newer.stillOwnsTransport());
        assertFalse(older.stillOwnsTransport());

        CheckpointRequestGuard.RequestLease stale =
                CheckpointRequestGuard.consumeFullyGrounded(older.marker);

        assertFalse(stale.checkpointCurrent);
        assertEquals("", stale.executionLeaseToken);
        assertEquals("", stale.snapshotFingerprint);
        assertEquals(0, CheckpointRequestGuard.consumedSnapshotCountForTest());
        assertTrue(newer.stillOwnsTransport());

        CheckpointRequestGuard.RequestLease current =
                CheckpointRequestGuard.consumeFullyGrounded(newer.marker);
        assertTrue(current.checkpointCurrent);
        assertEquals(newer.executionLeaseToken, current.executionLeaseToken);
        assertEquals(newer.snapshotFingerprint, current.snapshotFingerprint);
        assertEquals(1, CheckpointRequestGuard.consumedSnapshotCountForTest());
    }
}
