package com.emrah.canvaapprentice;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Runtime fail-closed policy for a task that is already bound to an existing Canva design.
 * This is deliberately independent from teacher prompts: a teacher reply cannot bypass it.
 */
public final class DesignContinuityPolicy {
    private static final double MAX_VISUAL_CONTINUITY_DISTANCE = 0.0350;

    private DesignContinuityPolicy() {}

    public static boolean allows(AgentAction action, String boundAnchor,
                                 boolean anchorVisible, boolean canvaHomeVisible) {
        return allows(action, boundAnchor, anchorVisible, canvaHomeVisible, false);
    }

    public static boolean allows(AgentAction action, String boundAnchor,
                                 boolean anchorVisible, boolean canvaHomeVisible,
                                 boolean matchesLastSafeEditorSnapshot) {
        if (action == null) return false;

        // Teacher-produced actions carry the execution lease that was current when the reply
        // was parsed. If a newer teacher request has started since then, this is a stale action.
        // Reject it before any Canva continuity checks can authorize a click/edit.
        if (!action.executionLeaseToken.isEmpty()
                && !TeacherExecutionLease.isGlobalCurrent(action.executionLeaseToken)) {
            return false;
        }

        String anchor = norm(boundAnchor);
        if (anchor.isEmpty()) return true;

        // Canva home/projects is never editor identity evidence. The bound design name is often
        // visible there as a project card, so neither anchor visibility nor a stale snapshot match
        // may authorize editing from home. Fail closed: permit only BACK recovery or opening the
        // exact already-bound design.
        if (canvaHomeVisible) {
            if (action.type == AgentAction.Type.BACK) return true;
            if (action.type == AgentAction.Type.CLICK_TEXT) {
                return anchor.equals(norm(action.target));
            }
            if (action.type == AgentAction.Type.CLICK_NODE) {
                return anchor.equals(norm(NodeTargetCodec.label(action.target)));
            }
            return false;
        }

        // Primary editor evidence: the bound design identity is visible in the current UI tree.
        if (anchorVisible) return true;

        // Secondary independent evidence: the current non-home UI tree exactly matches the last
        // snapshot that was learned only while this bound design had been positively verified.
        // This covers transient Canva states where the title/anchor temporarily disappears without
        // weakening the fail-closed rule for an unknown or changed editor.
        if (matchesLastSafeEditorSnapshot) return true;

        // BACK is the only generic recovery action allowed without design identity evidence.
        // It can leave a wrong/transient screen but cannot edit the unknown design.
        if (action.type == AgentAction.Type.BACK) return true;

        return false;
    }

    /**
     * Converts a lease-owned pre/post screenshot comparison into a conservative visual editor
     * continuity candidate. Invalid/non-finite distances fail closed. This does not override the
     * explicit Canva home/projects rejection in verifiesBoundDesignAfterAction().
     */
    public static boolean visualEditorContinuityFromDistance(double visualDistance) {
        return Double.isFinite(visualDistance)
                && visualDistance >= 0.0
                && visualDistance <= MAX_VISUAL_CONTINUITY_DISTANCE;
    }

    /**
     * Post-action success proof for learning memory. UI change alone is not success: once a task
     * is bound to an existing design, the resulting screen must still be positively attributable
     * to that design. Canva home/projects is explicitly rejected even when its project card shows
     * the bound anchor, because that is not editor identity evidence.
     */
    public static boolean verifiesBoundDesignAfterAction(String boundAnchor,
                                                         boolean anchorVisible,
                                                         boolean canvaHomeVisible,
                                                         boolean matchesLastSafeEditorSnapshot) {
        return verifiesBoundDesignAfterAction(
                boundAnchor,
                anchorVisible,
                canvaHomeVisible,
                matchesLastSafeEditorSnapshot,
                false
        );
    }

    /**
     * Visual editor continuity is an additional post-action proof channel for transient Canva
     * panels where the title disappears and the UI-tree fingerprint legitimately changes. It is
     * deliberately weaker than the home/projects guard: visual similarity can never turn Canva
     * home into editor identity evidence. The caller must only set this flag after comparing a
     * lease-owned pre-action screenshot from an already-authorized editor with the post-action
     * screenshot and passing a conservative visual-drift threshold.
     */
    public static boolean verifiesBoundDesignAfterAction(String boundAnchor,
                                                         boolean anchorVisible,
                                                         boolean canvaHomeVisible,
                                                         boolean matchesLastSafeEditorSnapshot,
                                                         boolean visualEditorContinuityVerified) {
        String anchor = norm(boundAnchor);
        if (anchor.isEmpty()) return true;
        if (canvaHomeVisible) return false;
        return anchorVisible || matchesLastSafeEditorSnapshot || visualEditorContinuityVerified;
    }

    private static String norm(String s) {
        String x = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace('ı', 'i');
        return x.replaceAll("\\s+", " ").trim();
    }
}
