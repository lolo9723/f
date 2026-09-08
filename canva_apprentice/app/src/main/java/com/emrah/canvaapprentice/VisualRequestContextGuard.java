package com.emrah.canvaapprentice;

/**
 * Fail-closed guard that binds a screenshot-backed teacher request to the exact
 * structural Canva state that existed immediately before capture.
 */
public final class VisualRequestContextGuard {
    private VisualRequestContextGuard() {}

    /**
     * Backwards-compatible form for callers that have already proven the task's
     * design anchor cannot change during capture.
     */
    public static boolean matches(
            String expectedPackage,
            String currentPackage,
            String expectedFingerprint,
            String currentFingerprint,
            String designAnchor,
            boolean anchorVisible,
            boolean looksLikeCanvaHome) {
        return matches(
                expectedPackage,
                currentPackage,
                expectedFingerprint,
                currentFingerprint,
                designAnchor,
                designAnchor,
                anchorVisible,
                looksLikeCanvaHome);
    }

    /**
     * Strong form used at asynchronous screenshot boundaries. Besides package
     * and tree identity, it requires the persisted design anchor itself to be
     * unchanged across capture. This prevents an old screenshot/tree pair from
     * being accepted after a task takeover, resume, or design-anchor rollover.
     */
    public static boolean matches(
            String expectedPackage,
            String currentPackage,
            String expectedFingerprint,
            String currentFingerprint,
            String expectedDesignAnchor,
            String currentDesignAnchor,
            boolean anchorVisible,
            boolean looksLikeCanvaHome) {
        if (expectedPackage == null || currentPackage == null
                || expectedFingerprint == null || currentFingerprint == null
                || expectedDesignAnchor == null || currentDesignAnchor == null) return false;
        if (!AgentConstants.CANVA_PACKAGE.equals(expectedPackage)
                || !AgentConstants.CANVA_PACKAGE.equals(currentPackage)) return false;
        if (expectedFingerprint.isEmpty() || !expectedFingerprint.equals(currentFingerprint)) return false;

        String expectedAnchor = expectedDesignAnchor.trim();
        String currentAnchor = currentDesignAnchor.trim();
        if (!expectedAnchor.equals(currentAnchor)) return false;
        if (expectedAnchor.isEmpty()) return true;
        return anchorVisible && !looksLikeCanvaHome;
    }
}
