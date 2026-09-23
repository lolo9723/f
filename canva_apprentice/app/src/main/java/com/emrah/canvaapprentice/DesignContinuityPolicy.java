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
        if (anchor.isEmpty()) {
            // No durable design identity means there is no authority to mutate or navigate by
            // teacher-selected UI labels yet. A generic CLICK_TEXT/CLICK_NODE can leave the
            // user's current design, open another project, or enter a creation flow even when its
            // label does not look like an explicit "Create" control. Fail closed until the
            // current editor has supplied a visible, plausible design anchor. BACK remains the
            // only non-targeted recovery action and cannot create/edit content.
            return action.type == AgentAction.Type.BACK;
        }

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

    public static boolean preActionBoundDesignVerified(String boundAnchor,
                                                       boolean anchorVisible,
                                                       boolean canvaHomeVisible,
                                                       boolean matchesLastSafeEditorSnapshot) {
        String anchor = norm(boundAnchor);
        if (anchor.isEmpty()) return false;
        if (canvaHomeVisible) return false;
        return anchorVisible || matchesLastSafeEditorSnapshot;
    }

    public static boolean finalDoneMayStop(String boundAnchor,
                                           boolean visualGrounded,
                                           boolean preActionBoundDesignVerified,
                                           boolean leaseOwnedVisualEvidence) {
        if (norm(boundAnchor).isEmpty()) return false;
        return visualGrounded && preActionBoundDesignVerified && leaseOwnedVisualEvidence;
    }

    public static boolean visualEditorContinuityFromDistance(double visualDistance) {
        return false;
    }

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
