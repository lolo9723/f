package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class CheckpointAuthorityCanonicalIdentityTest {
    @Before public void setUp() {
        CheckpointRequestGuard.resetForTest();
        TeacherExecutionLease.invalidateGlobal();
    }

    @After public void tearDown() {
        CheckpointRequestGuard.resetForTest();
        TeacherExecutionLease.invalidateGlobal();
    }

    @Test public void fullyGroundedBindingRejectsWhitespaceWithoutCreatingAuthority() {
        assertFalse(CheckpointRequestGuard.bindFullyGrounded(
                " CAA1_REPLY_req1|", "lease1", "snapshot1"));
        assertFalse(CheckpointRequestGuard.bindFullyGrounded(
                "CAA1_REPLY_req2|", "lease2\t", "snapshot2"));
        assertFalse(CheckpointRequestGuard.bindFullyGrounded(
                "CAA1_REPLY_req3|", "lease3", "snapshot3\n"));
        assertEquals(0, CheckpointRequestGuard.pendingRequestCountForTest());
    }

    @Test public void paddedLookupCannotBorrowCanonicalAuthority() {
        assertTrue(CheckpointRequestGuard.bindFullyGrounded(
                "CAA1_REPLY_req4|", "lease4", "snapshot4"));

        CheckpointRequestGuard.RequestLease padded =
                CheckpointRequestGuard.currentBoundRequestLease(" CAA1_REPLY_req4|");
        assertFalse(padded.checkpointCurrent);
        assertTrue(padded.executionLeaseToken.isEmpty());

        CheckpointRequestGuard.RequestLease exact =
                CheckpointRequestGuard.currentBoundRequestLease("CAA1_REPLY_req4|");
        assertTrue(exact.checkpointCurrent);
        assertEquals("lease4", exact.executionLeaseToken);
        assertEquals("snapshot4", exact.snapshotFingerprint);
    }

    @Test public void paddedConsumeFailsClosedWithoutConsumingExactRequest() {
        assertTrue(CheckpointRequestGuard.bindFullyGrounded(
                "CAA1_REPLY_req5|", "lease5", "snapshot5"));

        CheckpointRequestGuard.RequestLease padded =
                CheckpointRequestGuard.consumeFullyGrounded("CAA1_REPLY_req5| ");
        assertFalse(padded.checkpointCurrent);
        assertTrue(padded.executionLeaseToken.isEmpty());

        CheckpointRequestGuard.RequestLease exact =
                CheckpointRequestGuard.consumeFullyGrounded("CAA1_REPLY_req5|");
        assertTrue(exact.checkpointCurrent);
        assertEquals("lease5", exact.executionLeaseToken);
        assertEquals("snapshot5", exact.snapshotFingerprint);
    }
}
