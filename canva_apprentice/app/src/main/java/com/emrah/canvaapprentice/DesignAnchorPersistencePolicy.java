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
}
