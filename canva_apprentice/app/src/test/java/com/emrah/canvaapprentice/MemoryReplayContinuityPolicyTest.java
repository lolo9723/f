package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MemoryReplayContinuityPolicyTest {

    @Test public void blocksReplayAfterResumeUntilFreshSafeCheckpointExists() {
        assertFalse(MemoryReplayContinuityPolicy.mayRead(
                TaskState.Mode.RUNNING, "", "screen-A"));
    }

    @Test public void blocksReplayWhenCurrentSnapshotDiffersFromFreshSafeCheckpoint() {
        assertFalse(MemoryReplayContinuityPolicy.mayRead(
                TaskState.Mode.RUNNING, "screen-A", "screen-B"));
    }

    @Test public void allowsReplayOnlyForExactCurrentSafeSnapshotWhileRunning() {
        assertTrue(MemoryReplayContinuityPolicy.mayRead(
                TaskState.Mode.RUNNING, "screen-A", "screen-A"));
    }

    @Test public void blocksReplayOutsideRunningModeEvenWhenFingerprintMatches() {
        assertFalse(MemoryReplayContinuityPolicy.mayRead(
                TaskState.Mode.HUMAN_TAKEOVER, "screen-A", "screen-A"));
        assertFalse(MemoryReplayContinuityPolicy.mayRead(
                TaskState.Mode.STOPPED, "screen-A", "screen-A"));
    }
}
