package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class StaleSplitTeacherBindingTest {
    @Before public void setUp() {
        CheckpointRequestGuard.resetForTest();
        TeacherExecutionLease.invalidateGlobal();
    }

    @After public void tearDown() {
        CheckpointRequestGuard.resetForTest();
        TeacherExecutionLease.invalidateGlobal();
    }

    @Test public void olderPartialRequestCannotBecomeGroundedAfterLeaseRotation() {
        String oldMarker = "CAA1_REPLY_oldrequest|";
        String oldLease = TeacherExecutionLease.beginGlobal();
        CheckpointRequestGuard.bind(oldMarker, oldLease);

        String newerLease = TeacherExecutionLease.beginGlobal();
        assertTrue(TeacherExecutionLease.isGlobalCurrent(newerLease));

        assertFalse(CheckpointRequestGuard.bindSnapshot(oldMarker, "snapshot-old"));
        assertFalse(CheckpointRequestGuard.currentBoundRequestLease(oldMarker).checkpointCurrent);
    }

    @Test public void currentLegacySplitRequestCanStillBindItsExactSnapshot() {
        String marker = "CAA1_REPLY_currentrequest|";
        String lease = TeacherExecutionLease.beginGlobal();
        CheckpointRequestGuard.bind(marker, lease);

        assertTrue(CheckpointRequestGuard.bindSnapshot(marker, "snapshot-current"));
        CheckpointRequestGuard.RequestLease bound =
                CheckpointRequestGuard.currentBoundRequestLease(marker);
        assertTrue(bound.checkpointCurrent);
        assertTrue(lease.equals(bound.executionLeaseToken));
        assertTrue("snapshot-current".equals(bound.snapshotFingerprint));
    }

    @Test public void checkpointAdvancePreventsLateSnapshotUpgrade() {
        String marker = "CAA1_REPLY_checkpointold|";
        String lease = TeacherExecutionLease.beginGlobal();
        CheckpointRequestGuard.bind(marker, lease);
        CheckpointRequestGuard.onCheckpointCommitted();

        assertFalse(CheckpointRequestGuard.bindSnapshot(marker, "snapshot-after-checkpoint"));
        assertFalse(CheckpointRequestGuard.currentBoundRequestLease(marker).checkpointCurrent);
    }
}
