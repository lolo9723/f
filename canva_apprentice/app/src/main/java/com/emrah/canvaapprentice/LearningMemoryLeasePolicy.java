package com.emrah.canvaapprentice;

import java.util.function.Supplier;

/**
 * Prevents delayed/stale or malformed actions from contaminating learned navigation memory.
 * Every persisted experience must belong to the exact teacher execution lease that is still
 * current at record time. Empty/unleased actions are rejected fail-closed: production learning
 * is teacher-driven, so there is no safe provenance for a transition without an execution lease.
 */
public final class LearningMemoryLeasePolicy {
    private LearningMemoryLeasePolicy() {}

    public static boolean canRecord(AgentAction action) {
        return withCurrentLease(action, false, () -> true);
    }

    /**
     * Executes a learning-memory mutation while holding the same global execution-lease monitor
     * that protects teacher action ownership. Callers that derive design scope or task state
     * inside {@code operation} therefore cannot observe a current lease, lose it to a newer
     * teacher chain, and still persist under the newer chain's design identity.
     */
    public static <T> T withCurrentLease(AgentAction action, T staleValue, Supplier<T> operation) {
        if (action == null || operation == null) return staleValue;
        String token = action.executionLeaseToken;
        if (token == null || token.isEmpty()) return staleValue;
        return TeacherExecutionLease.withGlobalCurrent(token, staleValue, operation);
    }
}
