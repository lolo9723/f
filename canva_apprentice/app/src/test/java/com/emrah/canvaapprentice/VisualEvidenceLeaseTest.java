package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class VisualEvidenceLeaseTest {
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

    @Test public void invalidBindFailsClosedAndClearsEvidence() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        lease.bind("lease-a", "hash-a");
        lease.bind("", "hash-b");
        assertEquals("", lease.readIfOwnedBy("lease-a"));
        assertEquals("", lease.ownerTokenForTest());
    }

    @Test public void arbitraryTokenCannotObserveEvidence() {
        VisualEvidenceLease lease = new VisualEvidenceLease();
        lease.bind("lease-a", "hash-a");
        assertEquals("", lease.readIfOwnedBy("lease-x"));
        assertFalse(lease.clearIfOwnedBy("lease-x"));
        assertEquals("hash-a", lease.readIfOwnedBy("lease-a"));
    }
}
