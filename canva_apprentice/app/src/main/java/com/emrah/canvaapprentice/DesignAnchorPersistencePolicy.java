package com.emrah.canvaapprentice;

/**
 * Fail-closed persistence guard for design identity commits.
 * A live-editor observation must belong to the same teacher session that originated
 * the BIND_DESIGN action, remained current when observation began, and is still current
 * at the persistence boundary. Any session rollover makes the evidence stale.
 */
public final class DesignAnchorPersistencePolicy {
    private DesignAnchorPersistencePolicy() {}

    public static boolean mayCommit(TaskState.Mode mode,
                                    String observedTeacherSessionId,
                                    String currentTeacherSessionId,
                                    String targetAnchor) {
        return mayCommit(
                mode,
                observedTeacherSessionId,
                observedTeacherSessionId,
                currentTeacherSessionId,
                targetAnchor);
    }

    public static boolean mayCommit(TaskState.Mode mode,
                                    String actionTeacherSessionId,
                                    String observedTeacherSessionId,
                                    String currentTeacherSessionId,
                                    String targetAnchor) {
        if (mode != TaskState.Mode.RUNNING) return false;
        String action = actionTeacherSessionId == null ? "" : actionTeacherSessionId.trim();
        String observed = observedTeacherSessionId == null ? "" : observedTeacherSessionId.trim();
        String current = currentTeacherSessionId == null ? "" : currentTeacherSessionId.trim();
        String target = targetAnchor == null ? "" : targetAnchor.trim();
        if (action.isEmpty() || observed.isEmpty() || current.isEmpty() || target.isEmpty()) return false;
        return action.equals(observed) && observed.equals(current);
    }

    /**
     * Once a design identity has been bound, persistence must never silently retarget the task to a
     * different design. Re-binding the exact same normalized anchor is harmless/idempotent; changing
     * it requires a new explicit task rather than teacher authority alone.
     */
    public static boolean preservesBoundIdentity(String existingAnchor, String targetAnchor) {
        String existing = existingAnchor == null ? "" : existingAnchor.trim();
        String target = targetAnchor == null ? "" : targetAnchor.trim();
        if (target.isEmpty()) return false;
        return existing.isEmpty() || existing.equals(target);
    }
}
