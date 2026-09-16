package com.emrah.canvaapprentice;

import java.text.Normalizer;

/**
 * Fail-closed persistence guard for design identity commits.
 * A live-editor observation must belong to the same teacher session that originated
 * the BIND_DESIGN action, remained current when observation began, and is still current
 * at the persistence boundary. Any session rollover makes the evidence stale.
 */
public final class DesignAnchorPersistencePolicy {
    private static final String UNBOUND_SENTINEL = "UNBOUND";
    // A design title is copied into durable authority state and teacher prompts. Bound the
    // identity defensively so a corrupted/accessibility-injected megastring cannot become
    // persistent authority or cause unbounded prompt/state growth. This is deliberately much
    // larger than a normal human-readable Canva title and therefore is a safety ceiling, not a
    // product-title assumption.
    private static final int MAX_ANCHOR_CODE_POINTS = 512;

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
        // Existing durable identity must itself still be admissible. Otherwise a corrupted legacy
        // value could participate in an apparently idempotent rebind and regain authority.
        if (!existing.isEmpty() && !isPersistableAnchor(existing)) return false;
        return existing.isEmpty() || existing.equals(target);
    }

    static boolean isPersistableAnchor(String anchor) {
        String value = normalize(anchor);
        if (value.isEmpty() || UNBOUND_SENTINEL.equalsIgnoreCase(value)) return false;
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) return false;
        if (value.codePointCount(0, value.length()) > MAX_ANCHOR_CODE_POINTS) return false;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR
                    // Undefined/noncharacter code points have no stable textual or visual identity.
                    // Accessibility corruption can surface them as replacement-like glyphs, so they
                    // must never become a durable Canva design authority token.
                    || type == Character.UNASSIGNED
                    // Private-use glyphs have no stable cross-font visual meaning. They can render
                    // as blank/tofu/different symbols across Android/Canva and are therefore unsafe
                    // as a durable visual design identity.
                    || type == Character.PRIVATE_USE
                    // A lone UTF-16 surrogate is malformed Unicode. Persisting it would create a
                    // design identity that later resume-safety deliberately refuses to trust,
                    // stranding the task or creating inconsistent authority boundaries.
                    || type == Character.SURROGATE) {
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
