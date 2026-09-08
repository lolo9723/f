package com.emrah.canvaapprentice;

import android.view.accessibility.AccessibilityNodeInfo;

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
     * Unlike a visual inspection request, execution requires a non-empty bound
     * design identity. The teacher may inspect an unbound editor to identify the
     * exact existing design, but it must BIND_DESIGN before any screenshot-
     * grounded mutation can be authorized. This prevents visually plausible
     * edits from being applied to an unknown or accidentally opened design.
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
        if (expectedDesignAnchor == null || expectedDesignAnchor.trim().isEmpty()) return false;
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

    /**
     * Production bridge for the existing second-screenshot execution boundary.
     * VisualEvidenceLease first proves that package/tree/design identity still
     * equals the context captured for the teacher. We then re-read the live Canva
     * editor and route the final decision through matchesExecution(), adding the
     * bound-anchor-visible and not-home requirements that a raw pixel comparison
     * cannot prove.
     *
     * The evidence context is checked before the live reads, after those reads,
     * and once more after the pure matchesExecution() decision. The final check
     * closes the remaining rollover window where the persisted design/package/tree
     * could change immediately after the second check but before this helper returned
     * ALLOW to the screenshot callback.
     *
     * With no runtime evidence context this helper is neutral because callers such
     * as post-action visual verification intentionally consume the evidence first.
     * The execution SafetyGate independently rejects a visual mutation that reaches
     * it without live evidence, so this does not create a context-free execution path.
     */
    static boolean currentExecutionAllows(double visualDrift, double maxVisualDrift) {
        if (!VisualEvidenceLease.hasRuntimeExpectedContext()) return true;
        if (!VisualEvidenceLease.isRuntimeDesignContextCurrent()) return false;

        AgentAccessibilityService service = AgentAccessibilityService.INSTANCE;
        if (service == null) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        String packageName = root != null && root.getPackageName() != null
                ? root.getPackageName().toString() : "";
        if (!AgentConstants.CANVA_PACKAGE.equals(packageName) || root == null) return false;

        UiTreeSnapshot snapshot = UiTreeSnapshot.capture(root);
        TaskState state = new TaskStateRepository(service).load();
        String designAnchor = state.designAnchor == null ? "" : state.designAnchor.trim();
        boolean anchorVisible = !designAnchor.isEmpty() && snapshot.containsText(designAnchor);

        // Revalidate after all asynchronous/live reads. A rollover between the first
        // lease check and these reads must never be authorized by comparing the new
        // context to itself below.
        if (!VisualEvidenceLease.isRuntimeDesignContextCurrent()) return false;

        boolean executionMatches = currentExecutionAllows(
                visualDrift,
                maxVisualDrift,
                true,
                true,
                packageName,
                snapshot.stableFingerprint(),
                designAnchor,
                anchorVisible,
                snapshot.looksLikeCanvaHome());
        if (!executionMatches) return false;

        // Last-moment fail-closed recheck. The pure policy above uses the already-read
        // live values; if a task/design rollover happens immediately afterwards, that
        // old snapshot must not be allowed to authorize the pending visual action.
        return VisualEvidenceLease.isRuntimeDesignContextCurrent();
    }

    /** Pure policy form used by regression tests. */
    static boolean currentExecutionAllows(
            double visualDrift,
            double maxVisualDrift,
            boolean evidenceContextPresent,
            boolean evidenceContextCurrent,
            String packageName,
            String fingerprint,
            String designAnchor,
            boolean anchorVisible,
            boolean looksLikeCanvaHome) {
        if (!evidenceContextPresent) return true;
        if (!evidenceContextCurrent) return false;
        return matchesExecution(
                packageName,
                packageName,
                fingerprint,
                fingerprint,
                designAnchor,
                designAnchor,
                anchorVisible,
                looksLikeCanvaHome,
                visualDrift,
                maxVisualDrift);
    }
}
