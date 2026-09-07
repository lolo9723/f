package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SafeCheckpointCommitBoundaryTest {
    @Test public void unchangedBoundEditorMayCommit() {
        assertTrue(SafeSnapshotPolicy.commitBoundaryStillMatches(
                "tree-1", "tree-1", true, false));
    }

    @Test public void uiDriftAfterScreenshotMustFailClosed() {
        assertFalse(SafeSnapshotPolicy.commitBoundaryStillMatches(
                "tree-before", "tree-after", true, false));
    }

    @Test public void anchorDisappearingAtCommitMustFailClosed() {
        assertFalse(SafeSnapshotPolicy.commitBoundaryStillMatches(
                "tree-1", "tree-1", false, false));
    }

    @Test public void canvaHomeAtCommitMustFailClosedEvenIfAnchorTextAppears() {
        assertFalse(SafeSnapshotPolicy.commitBoundaryStillMatches(
                "tree-1", "tree-1", true, true));
    }

    @Test public void missingFingerprintMustFailClosed() {
        assertFalse(SafeSnapshotPolicy.commitBoundaryStillMatches(
                "", "tree-1", true, false));
        assertFalse(SafeSnapshotPolicy.commitBoundaryStillMatches(
                "tree-1", "", true, false));
    }

    @Test public void outerWhitespaceIsNormalizedButIdentityIsExact() {
        assertTrue(SafeSnapshotPolicy.commitBoundaryStillMatches(
                " tree-1 ", "tree-1", true, false));
        assertFalse(SafeSnapshotPolicy.commitBoundaryStillMatches(
                "tree-1", "tree-2", true, false));
    }
}
