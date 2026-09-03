package com.emrah.canvaapprentice;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Runtime fail-closed policy for a task that is already bound to an existing Canva design.
 * This is deliberately independent from teacher prompts: a teacher reply cannot bypass it.
 */
public final class DesignContinuityPolicy {
    private DesignContinuityPolicy() {}

    public static boolean allows(AgentAction action, String boundAnchor,
                                 boolean anchorVisible, boolean canvaHomeVisible) {
        String anchor = norm(boundAnchor);
        if (anchor.isEmpty()) return true;
        if (action == null) return false;

        // Strongest evidence: the bound design identity is visible in the current UI tree.
        if (anchorVisible) return true;

        // BACK is the only generic recovery action allowed without design identity evidence.
        // It can leave a wrong/transient screen but cannot edit the unknown design.
        if (action.type == AgentAction.Type.BACK) return true;

        // On Canva home/projects, permit only opening the exact already-bound design.
        // Never permit arbitrary clicks, coordinate gestures, text edits, or creation flows.
        if (canvaHomeVisible) {
            if (action.type == AgentAction.Type.CLICK_TEXT) {
                return anchor.equals(norm(action.target));
            }
            if (action.type == AgentAction.Type.CLICK_NODE) {
                return anchor.equals(norm(NodeTargetCodec.label(action.target)));
            }
        }

        return false;
    }

    private static String norm(String s) {
        String x = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace('ı', 'i');
        return x.replaceAll("\\s+", " ").trim();
    }
}
