package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public final class AtomicTeacherAuthorityBindingTest {
    @Before public void setUp() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @Test public void fullyGroundedBindPublishesCompleteTupleAtOnce() {
        String lease = TeacherExecutionLease.beginGlobal();

        assertTrue(CheckpointRequestGuard.bindFullyGrounded(
                "CAA1_REPLY_atomic1|", lease, "snapshot-A"));

        CheckpointRequestGuard.RequestLease bound =
                CheckpointRequestGuard.currentBoundRequestLease("CAA1_REPLY_atomic1|");
        assertTrue(bound.checkpointCurrent);
        assertEquals(lease, bound.executionLeaseToken);
        assertEquals("snapshot-A", bound.snapshotFingerprint);
    }

    @Test public void invalidAtomicBindNeverPublishesPartialAuthority() {
        String lease = TeacherExecutionLease.beginGlobal();

        assertFalse(CheckpointRequestGuard.bindFullyGrounded(
                "CAA1_REPLY_atomic2|", lease, ""));
        assertEquals(0, CheckpointRequestGuard.pendingRequestCountForTest());
        assertFalse(CheckpointRequestGuard.currentBoundRequestLease(
                "CAA1_REPLY_atomic2|").checkpointCurrent);
    }

    @Test public void duplicateAtomicMarkerPoisonsInsteadOfBorrowingNewLease() {
        TeacherRequestAuthority first = TeacherRequestAuthority.begin("sameMarker", "snapshot-old");
        assertTrue(first.isValid());
        assertTrue(first.stillOwnsTransport());

        TeacherRequestAuthority duplicate = TeacherRequestAuthority.begin("sameMarker", "snapshot-new");

        assertFalse(duplicate.isValid());
        assertFalse(first.stillOwnsTransport());
        CheckpointRequestGuard.RequestLease poisoned =
                CheckpointRequestGuard.currentBoundRequestLease(first.marker);
        assertFalse(poisoned.checkpointCurrent);
        assertTrue(poisoned.executionLeaseToken.isEmpty());
        assertTrue(poisoned.snapshotFingerprint.isEmpty());
    }
}
