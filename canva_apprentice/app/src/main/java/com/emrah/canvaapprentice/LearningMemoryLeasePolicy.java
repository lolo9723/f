package com.emrah.canvaapprentice;

/**
 * Prevents delayed/stale or malformed actions from contaminating learned navigation memory.
 * Every persisted experience must belong to the exact teacher execution lease that is still
 * current at record time. Empty/unleased actions are rejected fail-closed: production learning
 * is teacher-driven, so there is no safe provenance for a transition without an execution lease.
 */
public final class LearningMemoryLeasePolicy {
    private LearningMemoryLeasePolicy() {}

    public static boolean canRecord(AgentAction action) {
        if (action == null) return false;
        String token = action.executionLeaseToken;
        if (token == null || token.isEmpty()) return false;
        return TeacherExecutionLease.isGlobalCurrent(token);
    }
}
