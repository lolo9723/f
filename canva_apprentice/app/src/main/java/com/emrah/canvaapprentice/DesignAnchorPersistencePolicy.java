package com.emrah.canvaapprentice;

/**
 * Fail-closed persistence guard for design identity commits.
 * A live-editor observation must belong to the same teacher session that originated
 * the BIND_DESIGN action, remained current when observation began, and is still current
 * at the persistence boundary. Any session rollover makes the evidence stale.
 */
public final class DesignAnchorPersistencePolicy {
    private static final String UNBOUND_SENTINEL = "UNBOUND";

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
        String action = normalize(actionTeacherSessionId);
        String observed = normalize(observedTeacherSessionId);
        String current = normalize(currentTeacherSessionId);
        String target = normalize(targetAnchor);
        if (action.isEmpty() || observed.isEmpty() || current.isEmpty() || !isPersistableAnchor(target)) {
            return false;
        }
        return action.equals(observed) && observed.equals(current);
    }

    /**
     * Once a design identity has been bound, persistence must never silently retarget the task to a
     * different design. Re-binding the exact same normalized anchor is harmless/idempotent; changing
     * it requires a new explicit task rather than teacher authority alone.
     */
    public static boolean preservesBoundIdentity(String existingAnchor, String targetAnchor) {
        String existing = normalize(existingAnchor);
        String target = normalize(targetAnchor);
        if (!isPersistableAnchor(target)) return false;
        return existing.isEmpty() || existing.equals(target);
    }

    static boolean isPersistableAnchor(String anchor) {
        String value = normalize(anchor);
        if (value.isEmpty() || UNBOUND_SENTINEL.equalsIgnoreCase(value)) return false;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
