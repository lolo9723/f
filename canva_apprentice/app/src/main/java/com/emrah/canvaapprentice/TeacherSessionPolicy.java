package com.emrah.canvaapprentice;

public final class TeacherSessionPolicy {
    private TeacherSessionPolicy() {}

    public static boolean isCurrent(String expectedSessionId, String currentSessionId, TaskState.Mode mode) {
        return expectedSessionId != null && !expectedSessionId.isEmpty()
                && expectedSessionId.equals(currentSessionId)
                && mode == TaskState.Mode.RUNNING;
    }
}
