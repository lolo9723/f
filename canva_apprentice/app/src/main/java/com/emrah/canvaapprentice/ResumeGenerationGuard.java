package com.emrah.canvaapprentice;

/**
 * Monotonic generation guard for DEVAM ET / service-restore resume chains.
 * A delayed callback may act only while it still owns the generation that
 * started it. Starting or invalidating a resume chain makes all older
 * callbacks fail closed.
 *
 * The guarded action helpers deliberately perform the generation check and
 * callback while holding the same monitor. This prevents a check-then-act
 * race where another resume invalidates the generation after isCurrent()
 * succeeds but before the callback mutates resume state.
 */
final class ResumeGenerationGuard {
    private long generation = 0L;

    synchronized long begin() {
        generation++;
        return generation;
    }

    synchronized void invalidate() {
        generation++;
    }

    synchronized boolean isCurrent(long expectedGeneration) {
        return expectedGeneration == generation;
    }

    synchronized boolean runIfCurrent(long expectedGeneration, Runnable action) {
        if (expectedGeneration != generation || action == null) return false;
        action.run();
        return true;
    }

    synchronized boolean consumeIfCurrent(long expectedGeneration, Runnable action) {
        if (expectedGeneration != generation || action == null) return false;
        generation++;
        action.run();
        return true;
    }
}
