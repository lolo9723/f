package com.emrah.canvaapprentice;

/**
 * Monotonic generation guard for DEVAM ET / service-restore resume chains.
 * A delayed callback may act only while it still owns the generation that
 * started it. Starting or invalidating a resume chain makes all older
 * callbacks fail closed.
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
}
