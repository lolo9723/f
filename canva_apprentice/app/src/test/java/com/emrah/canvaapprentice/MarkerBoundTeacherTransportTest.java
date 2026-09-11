package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class MarkerBoundTeacherTransportTest {
    @Before public void setUp() {
        CheckpointRequestGuard.resetForTest();
        TeacherExecutionLease.invalidateGlobal();
    }

    @After public void tearDown() {
        CheckpointRequestGuard.resetForTest();
        TeacherExecutionLease.invalidateGlobal();
    }

    @Test public void exactMarkerReturnsOnlyItsOwnFullyGroundedLease() {
        String marker = "CAA1_REPLY_A|";
        String lease = TeacherExecutionLease.beginGlobal();
        CheckpointRequestGuard.bind(marker, lease);

        assertEquals("", CheckpointRequestGuard.currentBoundExecutionLease(marker));
        assertTrue(CheckpointRequestGuard.bindSnapshot(marker, "snapshot-a"));
        assertEquals(lease, CheckpointRequestGuard.currentBoundExecutionLease(marker));
    }

    @Test public void staleMarkerCannotBorrowNewerGlobalLease() {
        String oldMarker = "CAA1_REPLY_OLD|";
        String oldLease = TeacherExecutionLease.beginGlobal();
        CheckpointRequestGuard.bind(oldMarker, oldLease);
        assertTrue(CheckpointRequestGuard.bindSnapshot(oldMarker, "snapshot-old"));

        String newMarker = "CAA1_REPLY_NEW|";
        String newLease = TeacherExecutionLease.beginGlobal();
        CheckpointRequestGuard.bind(newMarker, newLease);
        assertTrue(CheckpointRequestGuard.bindSnapshot(newMarker, "snapshot-new"));

        assertEquals(oldLease, CheckpointRequestGuard.currentBoundExecutionLease(oldMarker));
        assertFalse(TeacherRequestLeasePolicy.transportStillOwns(
                CheckpointRequestGuard.currentBoundExecutionLease(oldMarker)));
        assertEquals(newLease, CheckpointRequestGuard.currentBoundExecutionLease(newMarker));
        assertTrue(TeacherRequestLeasePolicy.transportStillOwns(
                CheckpointRequestGuard.currentBoundExecutionLease(newMarker)));
    }

    @Test public void checkpointAdvanceInvalidatesTransportLookupWithoutConsumingReply() {
        String marker = "CAA1_REPLY_CHECKPOINT|";
        String lease = TeacherExecutionLease.beginGlobal();
        CheckpointRequestGuard.bind(marker, lease);
        assertTrue(CheckpointRequestGuard.bindSnapshot(marker, "snapshot"));

        CheckpointRequestGuard.onCheckpointCommitted();

        assertEquals("", CheckpointRequestGuard.currentBoundExecutionLease(marker));
        CheckpointRequestGuard.RequestLease consumed = CheckpointRequestGuard.consume(marker);
        assertFalse(consumed.checkpointCurrent);
        assertEquals(lease, consumed.executionLeaseToken);
    }
}
