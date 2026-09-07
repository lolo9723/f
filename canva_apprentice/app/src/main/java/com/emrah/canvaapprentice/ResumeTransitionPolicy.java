package com.emrah.canvaapprentice;

/** Fail-closed state transition policy for human-takeover resume requests. */
public final class ResumeTransitionPolicy {
    private ResumeTransitionPolicy() {}

    public static boolean mayResume(TaskState.Mode mode) {
        return mode == TaskState.Mode.HUMAN_TAKEOVER;
    }
}
