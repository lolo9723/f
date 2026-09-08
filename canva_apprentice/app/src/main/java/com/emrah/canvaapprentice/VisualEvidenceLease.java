package com.emrah.canvaapprentice;

/**
 * Single-owner holder for visual-before evidence used by screenshot-grounded actions.
 * Evidence is bound to the exact teacher execution lease that captured it. A stale
 * action can neither read, replace nor clear evidence belonging to a newer request.
 */
public final class VisualEvidenceLease {
    private String ownerExecutionToken = "";
    private String visualHash = "";
    private String ownerDesignAnchor = "";
    private boolean ownerDesignContextCaptured = false;

    // The production service has one VisualEvidenceLease. This static mirror lets the
    // pixel-distance boundary fail closed if the persisted design identity rolls over
    // during the asynchronous second screenshot, after the first evidence read.
    private static volatile String runtimeExpectedDesignAnchor = null;

    /**
     * Legacy/test-only owner binding. Invalid input is intentionally side-effect free:
     * a malformed or late callback must never erase valid evidence owned by another chain.
     * Production code should prefer bindIfExecutionCurrent().
     */
    synchronized void bind(String executionToken, String hash) {
        if (executionToken == null || executionToken.isEmpty() || hash == null || hash.isEmpty()) {
            return;
        }
        ownerExecutionToken = executionToken;
        visualHash = hash;
        ownerDesignAnchor = "";
        ownerDesignContextCaptured = false;
    }

    /**
     * Binds evidence only while the supplied execution token still owns the global
     * teacher execution lease. The ownership check and mutation are performed under
     * the same global lease monitor, eliminating a check-then-act race with a newer
     * teacher request. For the same live execution token, first successful bind wins.
     *
     * In production, the persisted design identity is captured at the same evidence
     * boundary. A later anchor rollover invalidates the evidence even when the pixels
     * and structural tree happen to look identical.
     */
    public synchronized boolean bindIfExecutionCurrent(String executionToken, String hash) {
        if (hash == null || hash.isEmpty()) return false;
        return TeacherExecutionLease.withGlobalCurrent(executionToken, false, () -> {
            String currentDesignAnchor = currentRuntimeDesignAnchor();
            boolean capturedDesignContext = currentDesignAnchor != null;
            if (isOwnedBy(executionToken)) {
                if (!visualHash.equals(hash)) return false;
                if (ownerDesignContextCaptured != capturedDesignContext) return false;
                return !capturedDesignContext || ownerDesignAnchor.equals(currentDesignAnchor);
            }
            ownerExecutionToken = executionToken;
            visualHash = hash;
            ownerDesignContextCaptured = capturedDesignContext;
            ownerDesignAnchor = capturedDesignContext ? currentDesignAnchor : "";
            runtimeExpectedDesignAnchor = capturedDesignContext ? currentDesignAnchor : null;
            return true;
        });
    }

    synchronized String readIfOwnedBy(String executionToken) {
        if (!isOwnedBy(executionToken)) return "";
        if (ownerDesignContextCaptured && !ownerDesignAnchor.equals(currentRuntimeDesignAnchor())) return "";
        return visualHash;
    }

    /** Returns evidence only if ownership, design identity and the live global execution lease agree atomically. */
    public synchronized String readIfExecutionCurrent(String executionToken) {
        return TeacherExecutionLease.withGlobalCurrent(
                executionToken,
                "",
                () -> readIfOwnedBy(executionToken)
        );
    }

    /**
     * Atomically returns and clears evidence for the still-current execution chain.
     * This makes visual-before evidence single-use: duplicate verification callbacks
     * cannot reuse the same screenshot proof after the first consumer has claimed it.
     */
    public synchronized String consumeIfExecutionCurrent(String executionToken) {
        return TeacherExecutionLease.withGlobalCurrent(executionToken, "", () -> {
            if (!isOwnedBy(executionToken)) return "";
            if (ownerDesignContextCaptured && !ownerDesignAnchor.equals(currentRuntimeDesignAnchor())) return "";
            String consumed = visualHash;
            clear();
            return consumed;
        });
    }

    synchronized boolean clearIfOwnedBy(String executionToken) {
        if (!isOwnedBy(executionToken)) return false;
        clear();
        return true;
    }

    /** Clears only the evidence owned by the still-current execution chain, atomically. */
    public synchronized boolean clearIfExecutionCurrent(String executionToken) {
        return TeacherExecutionLease.withGlobalCurrent(
                executionToken,
                false,
                () -> clearIfOwnedBy(executionToken)
        );
    }

    synchronized boolean isOwnedBy(String executionToken) {
        return executionToken != null
                && !executionToken.isEmpty()
                && executionToken.equals(ownerExecutionToken)
                && !visualHash.isEmpty();
    }

    /**
     * Called by the visual fingerprint execution boundary. No active production
     * evidence means ordinary fingerprint comparisons remain unaffected.
     */
    static boolean isRuntimeDesignContextCurrent() {
        String expected = runtimeExpectedDesignAnchor;
        if (expected == null) return true;
        String current = currentRuntimeDesignAnchor();
        return current != null && expected.equals(current);
    }

    static boolean designIdentityMatches(String expected, String current) {
        return expected != null && current != null && expected.trim().equals(current.trim());
    }

    private static String currentRuntimeDesignAnchor() {
        AgentAccessibilityService service = AgentAccessibilityService.INSTANCE;
        if (service == null) return null;
        TaskState state = new TaskStateRepository(service).load();
        return state.designAnchor == null ? null : state.designAnchor.trim();
    }

    /** Explicit lifecycle reset; never use this from asynchronous request callbacks. */
    public synchronized void clear() {
        ownerExecutionToken = "";
        visualHash = "";
        ownerDesignAnchor = "";
        ownerDesignContextCaptured = false;
        runtimeExpectedDesignAnchor = null;
    }

    synchronized String ownerTokenForTest() { return ownerExecutionToken; }
}
