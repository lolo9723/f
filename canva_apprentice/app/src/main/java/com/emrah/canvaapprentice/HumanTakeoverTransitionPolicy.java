package com.emrah.canvaapprentice;

/** Fail-closed state transition policy for entering human takeover. */
public final class HumanTakeoverTransitionPolicy {
    private HumanTakeoverTransitionPolicy() {}

    public static boolean mayPause(TaskState.Mode mode) {
        return mode == TaskState.Mode.RUNNING;
    }
}
