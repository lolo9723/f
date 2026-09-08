package com.emrah.canvaapprentice;

/**
 * Fail-closed guard that binds a screenshot-backed teacher request to the exact
 * structural Canva state that existed immediately before capture.
 */
public final class VisualRequestContextGuard {
    private VisualRequestContextGuard() {}

    public static boolean matches(
            String expectedPackage,
            String currentPackage,
            String expectedFingerprint,
            String currentFingerprint,
            String designAnchor,
            boolean anchorVisible,
            boolean looksLikeCanvaHome) {
        if (expectedPackage == null || currentPackage == null
                || expectedFingerprint == null || currentFingerprint == null) return false;
        if (!AgentConstants.CANVA_PACKAGE.equals(expectedPackage)
                || !AgentConstants.CANVA_PACKAGE.equals(currentPackage)) return false;
        if (expectedFingerprint.isEmpty() || !expectedFingerprint.equals(currentFingerprint)) return false;

        String anchor = designAnchor == null ? "" : designAnchor.trim();
        if (anchor.isEmpty()) return true;
        return anchorVisible && !looksLikeCanvaHome;
    }
}
