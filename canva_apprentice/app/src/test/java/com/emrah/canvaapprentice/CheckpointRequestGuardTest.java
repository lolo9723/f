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
        TeacherExecutionLease.invalidateGlobal();
    }

    @After public void tearDown() {
        CheckpointRequestGuard.resetForTest();
        TeacherExecutionLease.invalidateGlobal();
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

    @Test public void unknownOrDuplicateConsumeFailsClosedWithoutBorrowingLease() {
        CheckpointRequestGuard.RequestLease missing = CheckpointRequestGuard.consume("CAA1_REPLY_missing|");
        assertFalse(missing.checkpointCurrent);
        assertEquals("", missing.executionLeaseToken);

        CheckpointRequestGuard.bind("CAA1_REPLY_once|", "lease-once");
        assertTrue(CheckpointRequestGuard.consume("CAA1_REPLY_once|").checkpointCurrent);
        CheckpointRequestGuard.RequestLease duplicate = CheckpointRequestGuard.consume("CAA1_REPLY_once|");
        assertFalse(duplicate.checkpointCurrent);
        assertEquals("", duplicate.executionLeaseToken);
    }

    @Test public void duplicatePendingMarkerIsPoisonedSoOldReplyCannotBorrowNewLease() {
        CheckpointRequestGuard.bind("CAA1_REPLY_same|", "lease-old");
        CheckpointRequestGuard.bind("CAA1_REPLY_same|", "lease-new");

        CheckpointRequestGuard.RequestLease firstArrival = CheckpointRequestGuard.consume("CAA1_REPLY_same|");
        assertFalse(firstArrival.checkpointCurrent);
        assertEquals("", firstArrival.executionLeaseToken);
        assertEquals(-1L, firstArrival.checkpointGeneration);

        CheckpointRequestGuard.RequestLease laterArrival = CheckpointRequestGuard.consume("CAA1_REPLY_same|");
        assertFalse(laterArrival.checkpointCurrent);
        assertEquals("", laterArrival.executionLeaseToken);
    }

    @Test public void duplicateMarkerRemainsFailClosedAcrossCheckpointCommit() {
        CheckpointRequestGuard.bind("CAA1_REPLY_same|", "lease-old");
        CheckpointRequestGuard.bind("CAA1_REPLY_same|", "lease-new");
        CheckpointRequestGuard.onCheckpointCommitted();

        CheckpointRequestGuard.RequestLease lease = CheckpointRequestGuard.consume("CAA1_REPLY_same|");
        assertFalse(lease.checkpointCurrent);
        assertEquals("", lease.executionLeaseToken);
        assertEquals(-1L, lease.checkpointGeneration);
    }

    @Test public void heavyAbandonedRequestChurnEvictsOnlyOldestAndKeepsFreshLease() {
        for (int i = 0; i < 129; i++) {
            CheckpointRequestGuard.bind("CAA1_REPLY_req" + i + "|", "lease-" + i);
        }

        assertEquals(128, CheckpointRequestGuard.pendingRequestCountForTest());

        CheckpointRequestGuard.RequestLease oldest = CheckpointRequestGuard.consume("CAA1_REPLY_req0|");
        assertFalse(oldest.checkpointCurrent);
        assertEquals("", oldest.executionLeaseToken);

        CheckpointRequestGuard.RequestLease freshest = CheckpointRequestGuard.consume("CAA1_REPLY_req128|");
        assertTrue(freshest.checkpointCurrent);
        assertEquals("lease-128", freshest.executionLeaseToken);
    }

    @Test public void checkpointCommitUnderFullCapacityDoesNotEraseFreshInflightRequest() {
        for (int i = 0; i < 128; i++) {
            CheckpointRequestGuard.bind("CAA1_REPLY_old" + i + "|", "lease-old-" + i);
        }
        CheckpointRequestGuard.onCheckpointCommitted();
        CheckpointRequestGuard.bind("CAA1_REPLY_fresh|", "lease-fresh");

        assertEquals(128, CheckpointRequestGuard.pendingRequestCountForTest());
        CheckpointRequestGuard.RequestLease fresh = CheckpointRequestGuard.consume("CAA1_REPLY_fresh|");
        assertTrue(fresh.checkpointCurrent);
        assertEquals("lease-fresh", fresh.executionLeaseToken);
        assertEquals(1L, fresh.checkpointGeneration);
    }

    @Test public void exactSnapshotAuthorityMatchesOnlyOnce() {
        String token = TeacherExecutionLease.beginGlobal();
        CheckpointRequestGuard.bind("CAA1_REPLY_exact|", token);
        assertTrue(CheckpointRequestGuard.bindSnapshot("CAA1_REPLY_exact|", "fp-exact"));

        CheckpointRequestGuard.RequestLease lease = CheckpointRequestGuard.consume("CAA1_REPLY_exact|");
        assertTrue(lease.checkpointCurrent);
        assertEquals("fp-exact", lease.snapshotFingerprint);
        assertEquals(1, CheckpointRequestGuard.consumedSnapshotCountForTest());

        assertTrue(CheckpointRequestGuard.consumeExecutionSnapshotIfMatches(token, "fp-exact"));
        assertEquals(0, CheckpointRequestGuard.consumedSnapshotCountForTest());
        assertFalse(CheckpointRequestGuard.consumeExecutionSnapshotIfMatches(token, "fp-exact"));
    }

    @Test public void snapshotMismatchBurnsOldAuthorityInsteadOfAllowingLaterReplay() {
        String token = TeacherExecutionLease.beginGlobal();
        CheckpointRequestGuard.bind("CAA1_REPLY_drift|", token);
        assertTrue(CheckpointRequestGuard.bindSnapshot("CAA1_REPLY_drift|", "fp-before"));
        CheckpointRequestGuard.consume("CAA1_REPLY_drift|");

        assertFalse(CheckpointRequestGuard.consumeExecutionSnapshotIfMatches(token, "fp-after"));
        assertEquals(0, CheckpointRequestGuard.consumedSnapshotCountForTest());
        assertFalse(CheckpointRequestGuard.consumeExecutionSnapshotIfMatches(token, "fp-before"));
    }

    @Test public void differentSnapshotRebindPoisonsPendingMarker() {
        String token = TeacherExecutionLease.beginGlobal();
        CheckpointRequestGuard.bind("CAA1_REPLY_rebind|", token);
        assertTrue(CheckpointRequestGuard.bindSnapshot("CAA1_REPLY_rebind|", "fp-a"));
        assertFalse(CheckpointRequestGuard.bindSnapshot("CAA1_REPLY_rebind|", "fp-b"));

        CheckpointRequestGuard.RequestLease lease = CheckpointRequestGuard.consume("CAA1_REPLY_rebind|");
        assertFalse(lease.checkpointCurrent);
        assertEquals("", lease.executionLeaseToken);
        assertEquals("", lease.snapshotFingerprint);
    }

    @Test public void newerExecutionLeaseCannotBorrowOlderSnapshotAuthority() {
        String oldToken = TeacherExecutionLease.beginGlobal();
        CheckpointRequestGuard.bind("CAA1_REPLY_old_snapshot|", oldToken);
        assertTrue(CheckpointRequestGuard.bindSnapshot("CAA1_REPLY_old_snapshot|", "fp-old"));
        CheckpointRequestGuard.consume("CAA1_REPLY_old_snapshot|");

        String newToken = TeacherExecutionLease.beginGlobal();
        assertFalse(CheckpointRequestGuard.consumeExecutionSnapshotIfMatches(oldToken, "fp-old"));
        assertFalse(CheckpointRequestGuard.consumeExecutionSnapshotIfMatches(newToken, "fp-old"));
    }
}
