package com.emrah.canvaapprentice;

/**
 * Fail-closed persistence guard for design identity commits.
 * A live-editor observation must belong to the same teacher session that was current
 * when the persistence attempt started. Session rollover means the evidence is stale.
 */
public final class DesignAnchorPersistencePolicy {
    private DesignAnchorPersistencePolicy() {}

    public static boolean mayCommit(TaskState.Mode mode,
                                    String observedTeacherSessionId,
                                    String currentTeacherSessionId,
                                    String targetAnchor) {
        if (mode != TaskState.Mode.RUNNING) return false;
        String observed = observedTeacherSessionId == null ? "" : observedTeacherSessionId.trim();
        String current = currentTeacherSessionId == null ? "" : currentTeacherSessionId.trim();
        String target = targetAnchor == null ? "" : targetAnchor.trim();
        if (observed.isEmpty() || current.isEmpty() || target.isEmpty()) return false;
        return observed.equals(current);
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
