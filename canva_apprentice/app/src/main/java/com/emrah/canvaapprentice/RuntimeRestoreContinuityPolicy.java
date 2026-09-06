package com.emrah.canvaapprentice;

/** Fail-closed policy for provenance that must never survive an Android process restoration. */
public final class RuntimeRestoreContinuityPolicy {
    private RuntimeRestoreContinuityPolicy() {}

    public static boolean mustInvalidate(TaskState.Mode restoredMode) {
        return restoredMode == TaskState.Mode.RUNNING
                || restoredMode == TaskState.Mode.HUMAN_TAKEOVER;
    }
}
