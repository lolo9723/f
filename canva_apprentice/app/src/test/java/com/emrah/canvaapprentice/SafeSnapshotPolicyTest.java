package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Persistence tests intentionally model design-scoped restore semantics, not just UI capture policy.
public final class SafeSnapshotPolicyTest {
    private static final String VALID_VISUAL =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" +
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" +
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" +
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

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

    @Test public void legacyStructuralOnlyAdmissionFailsClosedEvenWhenAnchorVisible() {
        assertFalse(SafeSnapshotPolicy.shouldMarkSafe("Annual Report", true, false));
    }

    @Test public void dualEvidenceRequiresFreshVisualProof() {
        assertFalse(SafeSnapshotPolicy.shouldMarkSafe("Annual Report", true, false, false));
        assertTrue(SafeSnapshotPolicy.shouldMarkSafe("Annual Report", true, false, true));
    }

    @Test public void dualEvidenceStillRejectsHomeUnboundAndMissingAnchor() {
        assertFalse(SafeSnapshotPolicy.shouldMarkSafe("Annual Report", true, true, true));
        assertFalse(SafeSnapshotPolicy.shouldMarkSafe("", true, false, true));
        assertFalse(SafeSnapshotPolicy.shouldMarkSafe("Annual Report", false, false, true));
    }

    @Test public void observedCheckpointAcceptsOnlySameSessionDesignAndObservation() {
        assertTrue(SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                TaskState.Mode.RUNNING,
                "Annual Report","Annual Report",
                "session-1","session-1",
                "tree-fp","tree-fp",VALID_VISUAL));
    }

    @Test public void observedCheckpointRejectsResumeSessionRotation() {
        assertFalse(SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                TaskState.Mode.RUNNING,
                "Annual Report","Annual Report",
                "session-2","session-1",
                "tree-fp","tree-fp",VALID_VISUAL));
    }

    @Test public void observedCheckpointRejectsDesignRebind() {
        assertFalse(SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                TaskState.Mode.RUNNING,
                "Quarterly Report","Annual Report",
                "session-1","session-1",
                "tree-fp","tree-fp",VALID_VISUAL));
    }

    @Test public void observedCheckpointRejectsStructuralDriftDuringScreenshot() {
        assertFalse(SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                TaskState.Mode.RUNNING,
                "Annual Report","Annual Report",
                "session-1","session-1",
                "tree-before","tree-after",VALID_VISUAL));
    }

    @Test public void observedCheckpointRejectsMissingVisualFingerprint() {
        assertFalse(SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                TaskState.Mode.RUNNING,
                "Annual Report","Annual Report",
                "session-1","session-1",
                "tree-fp","tree-fp",""));
    }

    @Test public void observedCheckpointRejectsMalformedVisualFingerprint() {
        assertFalse(SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                TaskState.Mode.RUNNING,
                "Annual Report","Annual Report",
                "session-1","session-1",
                "tree-fp","tree-fp","visual-fp"));
        assertFalse(SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                TaskState.Mode.RUNNING,
                "Annual Report","Annual Report",
                "session-1","session-1",
                "tree-fp","tree-fp",VALID_VISUAL.substring(1)));
        String invalidHex = "g" + VALID_VISUAL.substring(1);
        assertFalse(SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                TaskState.Mode.RUNNING,
                "Annual Report","Annual Report",
                "session-1","session-1",
                "tree-fp","tree-fp",invalidHex));
    }

    @Test public void observedCheckpointRejectsPausedOrStoppedRuntime() {
        assertFalse(SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                TaskState.Mode.HUMAN_TAKEOVER,
                "Annual Report","Annual Report",
                "session-1","session-1",
                "tree-fp","tree-fp",VALID_VISUAL));
        assertFalse(SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                TaskState.Mode.STOPPED,
                "Annual Report","Annual Report",
                "session-1","session-1",
                "tree-fp","tree-fp",VALID_VISUAL));
    }

    @Test public void observedCheckpointNormalizesOuterWhitespaceButNotIdentity() {
        assertTrue(SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                TaskState.Mode.RUNNING,
                "  Annual Report  ","Annual Report",
                " session-1 ","session-1",
                " tree-fp ","tree-fp"," "+VALID_VISUAL+" "));
        assertFalse(SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                TaskState.Mode.RUNNING,
                "Annual  Report","Annual Report",
                "session-1","session-1",
                "tree-fp","tree-fp",VALID_VISUAL));
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

    @Test public void restoredCheckpointMustBelongToExactCurrentDesign() {
        assertTrue(SafeSnapshotPolicy.mayRestoreCheckpoint(
                "Annual Report","Annual Report","fp-1"));
        assertFalse(SafeSnapshotPolicy.mayRestoreCheckpoint(
                "Annual Report","Quarterly Report","fp-1"));
    }

    @Test public void runtimeRestoreRequiresRunningModeEvenWithMatchingDesignEvidence() {
        assertTrue(SafeSnapshotPolicy.mayRestoreCheckpoint(
                TaskState.Mode.RUNNING,"Annual Report","Annual Report","fp-1"));
        assertFalse(SafeSnapshotPolicy.mayRestoreCheckpoint(
                TaskState.Mode.HUMAN_TAKEOVER,"Annual Report","Annual Report","fp-1"));
        assertFalse(SafeSnapshotPolicy.mayRestoreCheckpoint(
                TaskState.Mode.STOPPED,"Annual Report","Annual Report","fp-1"));
        assertFalse(SafeSnapshotPolicy.mayRestoreCheckpoint(
                TaskState.Mode.IDLE,"Annual Report","Annual Report","fp-1"));
    }

    @Test public void runtimeRestoreRejectsNullMode() {
        assertFalse(SafeSnapshotPolicy.mayRestoreCheckpoint(
                null,"Annual Report","Annual Report","fp-1"));
    }

    @Test public void legacyCheckpointWithoutOwnerMustFailClosed() {
        assertFalse(SafeSnapshotPolicy.mayRestoreCheckpoint(
                "Annual Report","","fp-1"));
        assertFalse(SafeSnapshotPolicy.mayRestoreCheckpoint(
                "Annual Report",null,"fp-1"));
    }

    @Test public void restoredCheckpointRequiresNonEmptyCurrentAnchorAndHash() {
        assertFalse(SafeSnapshotPolicy.mayRestoreCheckpoint(
                "","Annual Report","fp-1"));
        assertFalse(SafeSnapshotPolicy.mayRestoreCheckpoint(
                "Annual Report","Annual Report",""));
    }

    @Test public void restoreComparisonNormalizesOuterWhitespaceOnly() {
        assertTrue(SafeSnapshotPolicy.mayRestoreCheckpoint(
                "  Annual Report  ","Annual Report"," fp-1 "));
        assertFalse(SafeSnapshotPolicy.mayRestoreCheckpoint(
                "Annual  Report","Annual Report","fp-1"));
    }
}
