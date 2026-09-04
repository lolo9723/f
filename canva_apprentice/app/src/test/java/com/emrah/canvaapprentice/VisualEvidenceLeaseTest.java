package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class VisualEvidenceLeaseTest {
    @After public void resetExecutionLease() {
        TeacherExecutionLease.invalidateGlobal();
    }

    @Test public void currentOwnerCanReadAndClearEvidence() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        lease.bind("lease-a", "hash-a");
        assertEquals("hash-a", lease.readIfOwnedBy("lease-a"));
        assertTrue(lease.clearIfOwnedBy("lease-a"));
        assertEquals("", lease.readIfOwnedBy("lease-a"));
    }

    @Test public void staleOwnerCannotReadOrClearNewerEvidence() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        lease.bind("lease-a", "hash-a");
        lease.bind("lease-b", "hash-b");
        assertEquals("", lease.readIfOwnedBy("lease-a"));
        assertFalse(lease.clearIfOwnedBy("lease-a"));
        assertEquals("hash-b", lease.readIfOwnedBy("lease-b"));
        assertEquals("lease-b", lease.ownerTokenForTest());
    }

    @Test public void invalidBindFailsClosedWithoutErasingCurrentEvidence() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        lease.bind("lease-a", "hash-a");
        lease.bind("", "hash-b");
        assertEquals("hash-a", lease.readIfOwnedBy("lease-a"));
        assertEquals("lease-a", lease.ownerTokenForTest());
    }

    @Test public void malformedLateBindCannotEraseNewerEvidence() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        lease.bind("lease-new", "new-hash");
        lease.bind("lease-old", "");
        assertEquals("new-hash", lease.readIfOwnedBy("lease-new"));
        assertEquals("lease-new", lease.ownerTokenForTest());
    }

    @Test public void arbitraryTokenCannotObserveEvidence() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        lease.bind("lease-a", "hash-a");
        assertEquals("", lease.readIfOwnedBy("lease-x"));
        assertFalse(lease.clearIfOwnedBy("lease-x"));
        assertEquals("hash-a", lease.readIfOwnedBy("lease-a"));
    }

    @Test public void lateScreenshotCannotOverwriteEvidenceForNewerExecutionLease() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        String oldToken = TeacherExecutionLease.beginGlobal();
        assertTrue(lease.bindIfExecutionCurrent(oldToken, "old-hash"));

        String newToken = TeacherExecutionLease.beginGlobal();
        assertTrue(lease.bindIfExecutionCurrent(newToken, "new-hash"));
        assertFalse(lease.bindIfExecutionCurrent(oldToken, "late-old-hash"));

        assertEquals("", lease.readIfExecutionCurrent(oldToken));
        assertEquals("new-hash", lease.readIfExecutionCurrent(newToken));
        assertEquals(newToken, lease.ownerTokenForTest());
    }

    @Test public void staleExecutionCannotClearCurrentEvidence() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        String oldToken = TeacherExecutionLease.beginGlobal();
        assertTrue(lease.bindIfExecutionCurrent(oldToken, "old-hash"));

        String newToken = TeacherExecutionLease.beginGlobal();
        assertTrue(lease.bindIfExecutionCurrent(newToken, "new-hash"));
        assertFalse(lease.clearIfExecutionCurrent(oldToken));
        assertEquals("new-hash", lease.readIfExecutionCurrent(newToken));
    }

    @Test public void invalidatedExecutionCannotReadOrClearEvidence() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        String token = TeacherExecutionLease.beginGlobal();
        assertTrue(lease.bindIfExecutionCurrent(token, "hash"));
        TeacherExecutionLease.invalidateGlobal();

        assertEquals("", lease.readIfExecutionCurrent(token));
        assertFalse(lease.clearIfExecutionCurrent(token));
        assertEquals("hash", lease.readIfOwnedBy(token));
    }

    @Test public void currentExecutionConsumesEvidenceExactlyOnce() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        String token = TeacherExecutionLease.beginGlobal();
        assertTrue(lease.bindIfExecutionCurrent(token, "hash"));

        assertEquals("hash", lease.consumeIfExecutionCurrent(token));
        assertEquals("", lease.consumeIfExecutionCurrent(token));
        assertEquals("", lease.readIfExecutionCurrent(token));
        assertEquals("", lease.ownerTokenForTest());
    }

    @Test public void staleExecutionCannotConsumeNewerEvidence() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        String oldToken = TeacherExecutionLease.beginGlobal();
        assertTrue(lease.bindIfExecutionCurrent(oldToken, "old-hash"));

        String newToken = TeacherExecutionLease.beginGlobal();
        assertTrue(lease.bindIfExecutionCurrent(newToken, "new-hash"));

        assertEquals("", lease.consumeIfExecutionCurrent(oldToken));
        assertEquals("new-hash", lease.readIfExecutionCurrent(newToken));
        assertEquals("new-hash", lease.consumeIfExecutionCurrent(newToken));
    }

    @Test public void invalidatedExecutionCannotConsumeEvidence() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        String token = TeacherExecutionLease.beginGlobal();
        assertTrue(lease.bindIfExecutionCurrent(token, "hash"));
        TeacherExecutionLease.invalidateGlobal();

        assertEquals("", lease.consumeIfExecutionCurrent(token));
        assertEquals("hash", lease.readIfOwnedBy(token));
    }

    @Test public void sameExecutionCannotReplaceFirstVisualEvidence() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        String token = TeacherExecutionLease.beginGlobal();
        assertTrue(lease.bindIfExecutionCurrent(token, "first-hash"));

        assertFalse(lease.bindIfExecutionCurrent(token, "late-second-hash"));
        assertEquals("first-hash", lease.readIfExecutionCurrent(token));
        assertEquals("first-hash", lease.consumeIfExecutionCurrent(token));
    }

    @Test public void sameExecutionIdenticalRebindIsIdempotent() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        String token = TeacherExecutionLease.beginGlobal();
        assertTrue(lease.bindIfExecutionCurrent(token, "same-hash"));

        assertTrue(lease.bindIfExecutionCurrent(token, "same-hash"));
        assertEquals("same-hash", lease.consumeIfExecutionCurrent(token));
        assertEquals("", lease.consumeIfExecutionCurrent(token));
    }
}
