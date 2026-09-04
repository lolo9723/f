package com.emrah.canvaapprentice;

/**
 * Single-process epoch guard for asynchronous teacher callbacks.
 * Any task lifecycle boundary invalidates all previously issued callbacks.
 */
public final class TeacherRequestEpoch {
    private long epoch = 1L;

    public synchronized long capture() {
        return epoch;
    }

    public synchronized long invalidate() {
        if (epoch == Long.MAX_VALUE) epoch = 1L;
        else epoch++;
        return epoch;
    }

    public synchronized boolean isCurrent(long token) {
        return token == epoch;
    }
}
