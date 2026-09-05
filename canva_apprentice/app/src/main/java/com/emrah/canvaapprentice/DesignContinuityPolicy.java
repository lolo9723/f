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

        if (!action.executionLeaseToken.isEmpty()
                && !TeacherExecutionLease.isGlobalCurrent(action.executionLeaseToken)) {
            return false;
        }

        String anchor = norm(boundAnchor);
        if (anchor.isEmpty()) return true;

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

        if (anchorVisible) return true;
        if (matchesLastSafeEditorSnapshot) return true;
        if (action.type == AgentAction.Type.BACK) return true;

        return false;
    }

    /**
     * Independent pre-action proof used by visual continuity. A positive proof must already refer
     * to a bound design, must not come from Canva home/projects, and must be backed either by the
     * bound anchor being visible or by an exact previously-safe editor snapshot.
     *
     * Keeping this calculation here prevents callers from accidentally treating "task unbound",
     * "home screen", or a generic visual similarity signal as proof of design identity.
     */
    public static boolean preActionBoundDesignVerified(String boundAnchor,
                                                       boolean anchorVisible,
                                                       boolean canvaHomeVisible,
                                                       boolean matchesLastSafeEditorSnapshot) {
        String anchor = norm(boundAnchor);
        if (anchor.isEmpty()) return false;
        if (canvaHomeVisible) return false;
        return anchorVisible || matchesLastSafeEditorSnapshot;
    }

    /**
     * Visual similarity by itself is never design identity. This legacy entry point intentionally
     * remains fail-closed so callers cannot accidentally authorize continuity from distance alone.
     */
    public static boolean visualEditorContinuityFromDistance(double visualDistance) {
        return false;
    }

    /**
     * Three-factor visual continuity proof for transient Canva editor panels:
     *  1) the pre-action UI was independently proven to belong to the bound design,
     *  2) the visual evidence belongs to the current execution lease,
     *  3) the post-action screenshot remains within a very small finite visual distance.
     *
     * Missing any factor fails closed. This method does not override an explicit Canva-home signal;
     * verifiesBoundDesignAfterAction() still rejects home/projects even with a positive proof.
     */
    public static boolean visualEditorContinuityFromDistance(double visualDistance,
                                                             boolean preActionBoundDesignVerified,
                                                             boolean leaseOwnedVisualEvidence) {
        if (!preActionBoundDesignVerified || !leaseOwnedVisualEvidence) return false;
        if (!Double.isFinite(visualDistance)
                || visualDistance < 0.0
                || visualDistance > MAX_VISUAL_CONTINUITY_DISTANCE) {
            return false;
        }
        return true;
    }

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
     * Visual editor continuity is accepted only when the caller has already produced an explicit
     * independently verified visualEditorContinuityVerified flag.
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
