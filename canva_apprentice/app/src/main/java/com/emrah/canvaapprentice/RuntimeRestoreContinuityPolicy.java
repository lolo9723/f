package com.emrah.canvaapprentice;

/** Fail-closed policy for provenance that must never survive an Android process restoration. */
public final class RuntimeRestoreContinuityPolicy {
    private RuntimeRestoreContinuityPolicy() {}

    public static boolean mustInvalidate(TaskState.Mode restoredMode) {
        // Runtime continuity evidence is never durable evidence. Clear it on every fresh process,
        // including IDLE/STOPPED or an unknown future mode, so a stale checkpoint cannot become
        // authoritative merely because lifecycle/state handling changes later.
        return true;
    }
}
