package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SafeSnapshotPolicyTest {
    @Test public void unboundTaskMustNotLearnCanvaHomeAsSafe() {
        assertFalse(SafeSnapshotPolicy.shouldMarkSafe("", false, true));
    }

    @Test public void unboundNonHomeSurfaceMustAlsoFailClosed() {
        assertFalse(SafeSnapshotPolicy.shouldMarkSafe("", false, false));
    }

    @Test public void whitespaceOnlyAnchorMustNotCreateContinuityCheckpoint() {
        assertFalse(SafeSnapshotPolicy.shouldMarkSafe("   ", false, false));
    }

    @Test public void boundDesignOnCanvaHomeIsNeverLearnedAsSafe() {
        assertFalse(SafeSnapshotPolicy.shouldMarkSafe("Annual Report", true, true));
    }

    @Test public void boundDesignMissingFromUnknownEditorIsNotSafe() {
        assertFalse(SafeSnapshotPolicy.shouldMarkSafe("Annual Report", false, false));
    }

    @Test public void boundDesignVisibleInsideEditorMayRefreshSafeCheckpoint() {
        assertTrue(SafeSnapshotPolicy.shouldMarkSafe("Annual Report", true, false));
    }

    @Test public void repositoryCheckpointRequiresRunningMode() {
        assertFalse(SafeSnapshotPolicy.mayPersistCheckpoint(
                TaskState.Mode.HUMAN_TAKEOVER,"Annual Report","fp-1"));
        assertFalse(SafeSnapshotPolicy.mayPersistCheckpoint(
                TaskState.Mode.STOPPED,"Annual Report","fp-1"));
        assertFalse(SafeSnapshotPolicy.mayPersistCheckpoint(
                TaskState.Mode.IDLE,"Annual Report","fp-1"));
    }

    @Test public void repositoryCheckpointRequiresBoundDesign() {
        assertFalse(SafeSnapshotPolicy.mayPersistCheckpoint(
                TaskState.Mode.RUNNING,"","fp-1"));
        assertFalse(SafeSnapshotPolicy.mayPersistCheckpoint(
                TaskState.Mode.RUNNING,"   ","fp-1"));
    }

    @Test public void repositoryCheckpointRequiresUsableFingerprint() {
        assertFalse(SafeSnapshotPolicy.mayPersistCheckpoint(
                TaskState.Mode.RUNNING,"Annual Report",""));
        assertFalse(SafeSnapshotPolicy.mayPersistCheckpoint(
                TaskState.Mode.RUNNING,"Annual Report","   "));
    }

    @Test public void repositoryCheckpointAcceptsOnlyRunningBoundNonEmptyState() {
        assertTrue(SafeSnapshotPolicy.mayPersistCheckpoint(
                TaskState.Mode.RUNNING,"Annual Report","fp-1"));
    }
}
