package com.emrah.canvaapprentice;

/**
 * Fail-closed guard that binds a screenshot-backed teacher request to the exact
 * structural Canva state that existed immediately before capture.
 */
public final class VisualRequestContextGuard {
    private static final double HARD_MAX_EXECUTION_DRIFT = 0.0100;

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
     *
     * An unbound request is also forbidden on Canva home/projects. A visual
     * teacher must never receive authority to guess which existing design to
     * open from a gallery-like screen before exact design identity is bound.
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
        if (expectedAnchor.isEmpty()) return !looksLikeCanvaHome;
        return anchorVisible && !looksLikeCanvaHome;
    }

    /**
     * Execution-boundary form for screenshot-grounded actions. A visual action
     * may execute only if the live Canva package, structural fingerprint and
     * persisted design identity still match the state that was grounded for the
     * teacher, and the second screenshot remains within the permitted drift.
     *
     * The caller-provided threshold is itself capped at the audited production
     * ceiling. This prevents a future call site from accidentally weakening the
     * visual safety boundary by passing a larger tolerance.
     *
     * This method intentionally fails closed for NaN/infinite/negative drift or
     * invalid thresholds so a malformed visual comparison cannot authorize an
     * otherwise stale coordinate action.
     */
    public static boolean matchesExecution(
            String expectedPackage,
            String currentPackage,
            String expectedFingerprint,
            String currentFingerprint,
            String expectedDesignAnchor,
            String currentDesignAnchor,
            boolean anchorVisible,
            boolean looksLikeCanvaHome,
            double visualDrift,
            double maxVisualDrift) {
        if (!Double.isFinite(visualDrift) || !Double.isFinite(maxVisualDrift)
                || visualDrift < 0.0 || maxVisualDrift <= 0.0
                || maxVisualDrift > HARD_MAX_EXECUTION_DRIFT) return false;
        if (visualDrift >= maxVisualDrift) return false;
        return matches(
                expectedPackage,
                currentPackage,
                expectedFingerprint,
                currentFingerprint,
                expectedDesignAnchor,
                currentDesignAnchor,
                anchorVisible,
                looksLikeCanvaHome);
    }
}
