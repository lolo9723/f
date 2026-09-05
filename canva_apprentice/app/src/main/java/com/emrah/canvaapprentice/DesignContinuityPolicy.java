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
     * Visual similarity by itself is not design identity. Two different Canva editors can have
     * nearly identical chrome/layout and therefore a tiny luminance-fingerprint distance. Until
     * the runtime supplies an independent pre-action proof that the screenshot belongs to the
     * bound design, visual-only continuity must fail closed.
     *
     * The finite/range checks are intentionally retained here so malformed values remain rejected
     * if this channel is later re-enabled with a second identity factor.
     */
    public static boolean visualEditorContinuityFromDistance(double visualDistance) {
        if (!Double.isFinite(visualDistance)
                || visualDistance < 0.0
                || visualDistance > MAX_VISUAL_CONTINUITY_DISTANCE) {
            return false;
        }
        return false;
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
     * independently verified visualEditorContinuityVerified flag. The current runtime deliberately
     * does not create that flag from visual distance alone.
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
