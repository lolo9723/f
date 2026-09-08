package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class CheckpointRequestGuardTest {
    @Before public void setUp() {
        CheckpointRequestGuard.resetForTest();
    }

    @After public void tearDown() {
        CheckpointRequestGuard.resetForTest();
    }

    @Test public void requestRemainsCurrentWhenNoCheckpointCommits() {
        CheckpointRequestGuard.bind("CAA1_REPLY_req|", "lease-a");
        CheckpointRequestGuard.RequestLease lease = CheckpointRequestGuard.consume("CAA1_REPLY_req|");
        assertTrue(lease.checkpointCurrent);
        assertEquals("lease-a", lease.executionLeaseToken);
        assertEquals(0L, lease.checkpointGeneration);
    }

    @Test public void checkpointCommitStalesOlderTeacherRequest() {
        CheckpointRequestGuard.bind("CAA1_REPLY_old|", "lease-old");
        CheckpointRequestGuard.onCheckpointCommitted();
        CheckpointRequestGuard.RequestLease lease = CheckpointRequestGuard.consume("CAA1_REPLY_old|");
        assertFalse(lease.checkpointCurrent);
        assertEquals("lease-old", lease.executionLeaseToken);
        assertEquals(0L, lease.checkpointGeneration);
        assertEquals(1L, CheckpointRequestGuard.currentGenerationForTest());
    }

    @Test public void newerRequestAfterCheckpointIsCurrent() {
        CheckpointRequestGuard.onCheckpointCommitted();
        CheckpointRequestGuard.bind("CAA1_REPLY_new|", "lease-new");
        CheckpointRequestGuard.RequestLease lease = CheckpointRequestGuard.consume("CAA1_REPLY_new|");
        assertTrue(lease.checkpointCurrent);
        assertEquals("lease-new", lease.executionLeaseToken);
        assertEquals(1L, lease.checkpointGeneration);
    }

    @Test public void staleReplyCannotBorrowFreshPostCheckpointLease() {
        CheckpointRequestGuard.bind("CAA1_REPLY_old|", "lease-old");
        CheckpointRequestGuard.onCheckpointCommitted();
        CheckpointRequestGuard.bind("CAA1_REPLY_fresh|", "lease-fresh");

        CheckpointRequestGuard.RequestLease stale = CheckpointRequestGuard.consume("CAA1_REPLY_old|");
        assertFalse(stale.checkpointCurrent);
        assertEquals("lease-old", stale.executionLeaseToken);
        assertEquals(0L, stale.checkpointGeneration);

        CheckpointRequestGuard.RequestLease fresh = CheckpointRequestGuard.consume("CAA1_REPLY_fresh|");
        assertTrue(fresh.checkpointCurrent);
        assertEquals("lease-fresh", fresh.executionLeaseToken);
        assertEquals(1L, fresh.checkpointGeneration);
    }

    @Test public void unknownOrDuplicateMarkerFailsClosedWithoutBorrowingLease() {
        CheckpointRequestGuard.RequestLease missing = CheckpointRequestGuard.consume("CAA1_REPLY_missing|");
        assertFalse(missing.checkpointCurrent);
        assertEquals("", missing.executionLeaseToken);

        CheckpointRequestGuard.bind("CAA1_REPLY_once|", "lease-once");
        assertTrue(CheckpointRequestGuard.consume("CAA1_REPLY_once|").checkpointCurrent);
        CheckpointRequestGuard.RequestLease duplicate = CheckpointRequestGuard.consume("CAA1_REPLY_once|");
        assertFalse(duplicate.checkpointCurrent);
        assertEquals("", duplicate.executionLeaseToken);
    }
}
