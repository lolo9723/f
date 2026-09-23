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
            if (action.type == AgentAction.Type.CLICK_TEXT) {
                return !isExplicitCreationTarget(action.target);
            }
            if (action.type == AgentAction.Type.CLICK_NODE) {
                return !isExplicitCreationTarget(NodeTargetCodec.label(action.target));
            }
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

    private static boolean isExplicitCreationTarget(String rawTarget) {
        String target = creationNorm(rawTarget);
        if (target.isEmpty()) return false;
        return target.equals("create")
                || target.equals("create a design")
                || target.equals("create design")
                || target.equals("create new design")
                || target.equals("new design")
                || target.equals("olustur")
                || target.equals("tasarim olustur")
                || target.equals("yeni tasarim")
                || target.equals("yeni bir tasarim olustur");
    }

    private static String creationNorm(String s) {
        return norm(s)
                .replaceAll("[\\p{P}\\p{S}\\p{C}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String norm(String s) {
        String x = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace('ı', 'i');
        return x.replaceAll("\\s+", " ").trim();
    }
}
