package com.emrah.canvaapprentice;

/**
 * Prevents delayed/stale teacher actions from contaminating learned navigation memory.
 * Teacher-produced actions have a non-empty execution lease token. Once a newer teacher
 * request rotates that lease, the old action may no longer write success/failure evidence.
 */
public final class LearningMemoryLeasePolicy {
    private LearningMemoryLeasePolicy() {}

    public static boolean canRecord(AgentAction action) {
        if (action == null) return false;
        String token = action.executionLeaseToken;
        return token == null || token.isEmpty() || TeacherExecutionLease.isGlobalCurrent(token);
    }
}
