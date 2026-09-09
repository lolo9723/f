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
}
