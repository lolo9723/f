package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LearningMemoryWriteContextPolicyTest {
    @Test public void allowsOnlyExactLiveSafeCheckpointOfBoundRunningDesign() {
        assertTrue(LearningMemoryWriteContextPolicy.mayCommit(
                TaskState.Mode.RUNNING,"fp-1","fp-1","My Design"));
    }

    @Test public void rejectsStaleVerificationAfterCheckpointMoves() {
        assertFalse(LearningMemoryWriteContextPolicy.mayCommit(
                TaskState.Mode.RUNNING,"fp-1","fp-2","My Design"));
    }

    @Test public void rejectsVerificationAfterDesignRebindClearsCheckpoint() {
        assertFalse(LearningMemoryWriteContextPolicy.mayCommit(
                TaskState.Mode.RUNNING,"fp-1","","Other Design"));
    }

    @Test public void rejectsUnboundOrHumanTakeoverContext() {
        assertFalse(LearningMemoryWriteContextPolicy.mayCommit(
                TaskState.Mode.RUNNING,"fp-1","fp-1",""));
        assertFalse(LearningMemoryWriteContextPolicy.mayCommit(
                TaskState.Mode.HUMAN_TAKEOVER,"fp-1","fp-1","My Design"));
    }

    @Test public void rejectsPaddedFingerprintInsteadOfNormalizingAuthority() {
        assertFalse(LearningMemoryWriteContextPolicy.mayCommit(
                TaskState.Mode.RUNNING," fp-1","fp-1","My Design"));
        assertFalse(LearningMemoryWriteContextPolicy.mayCommit(
                TaskState.Mode.RUNNING,"fp-1","fp-1 ","My Design"));
    }

    @Test public void rejectsPaddedOrControlCharacterDesignIdentity() {
        assertFalse(LearningMemoryWriteContextPolicy.mayCommit(
                TaskState.Mode.RUNNING,"fp-1","fp-1"," My Design"));
        assertFalse(LearningMemoryWriteContextPolicy.mayCommit(
                TaskState.Mode.RUNNING,"fp-1","fp-1","My\tDesign"));
    }

    @Test public void rejectsControlCharacterFingerprintEvenWhenBothSidesMatch() {
        assertFalse(LearningMemoryWriteContextPolicy.mayCommit(
                TaskState.Mode.RUNNING,"fp\n1","fp\n1","My Design"));
    }

    @Test public void rejectsEmbeddedWhitespaceFingerprintEvenWhenBothSidesMatch() {
        assertFalse(LearningMemoryWriteContextPolicy.mayCommit(
                TaskState.Mode.RUNNING,"fp 1","fp 1","My Design"));
        assertFalse(LearningMemoryWriteContextPolicy.mayCommit(
                TaskState.Mode.RUNNING,"fp\t1","fp\t1","My Design"));
        assertFalse(LearningMemoryWriteContextPolicy.mayCommit(
                TaskState.Mode.RUNNING,"fp\u00a01","fp\u00a01","My Design"));
    }

    @Test public void ordinaryEmbeddedSpacesRemainValidForDisplayDesignNames() {
        assertTrue(LearningMemoryWriteContextPolicy.mayCommit(
                TaskState.Mode.RUNNING,"fp-1","fp-1","My Existing Design"));
    }
}
