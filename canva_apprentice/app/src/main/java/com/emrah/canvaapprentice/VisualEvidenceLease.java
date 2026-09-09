package com.emrah.canvaapprentice;

import android.view.accessibility.AccessibilityNodeInfo;

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
    // pixel-distance boundary fail closed if package/tree/design identity rolls over
    // during the asynchronous second screenshot, after the first evidence read.
    // The mirror itself is execution-owned: lifecycle cleanup from an older service
    // instance must never erase runtime evidence that belongs to a newer execution.
    private static volatile RuntimeContext runtimeExpectedContext = null;
    private static volatile String runtimeExpectedOwnerExecutionToken = "";

    private static final class RuntimeContext {
        final String packageName;
        final String fingerprint;
        final String designAnchor;

        RuntimeContext(String packageName, String fingerprint, String designAnchor) {
            this.packageName = packageName;
            this.fingerprint = fingerprint;
            this.designAnchor = designAnchor;
        }
    }

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
     * In production, the live Canva package, structural fingerprint and persisted
     * design identity are captured at the same evidence boundary. Any rollover during
     * the later execution screenshot invalidates the evidence even when pixels happen
     * to remain visually similar. If the accessibility service is live but that exact
     * Canva runtime context cannot be captured, binding fails closed rather than
     * creating context-free visual evidence.
     */
    public synchronized boolean bindIfExecutionCurrent(String executionToken, String hash) {
        if (hash == null || hash.isEmpty()) return false;
        return TeacherExecutionLease.withGlobalCurrent(executionToken, false, () -> {
            RuntimeContext currentContext = currentRuntimeContext();
            boolean serviceActive = AgentAccessibilityService.INSTANCE != null;
            if (!mayBindRuntimeEvidence(serviceActive, currentContext != null)) return false;

            String currentDesignAnchor = currentContext == null ? null : currentContext.designAnchor;
            boolean capturedDesignContext = currentDesignAnchor != null;
            if (isOwnedBy(executionToken)) {
                if (!visualHash.equals(hash)) return false;
                if (ownerDesignContextCaptured != capturedDesignContext) return false;
                if (capturedDesignContext && !ownerDesignAnchor.equals(currentDesignAnchor)) return false;
                RuntimeContext expected = runtimeExpectedContext;
                if (expected != null && !executionToken.equals(runtimeExpectedOwnerExecutionToken)) return false;
                return expected == null || (currentContext != null && executionContextMatches(
                        expected.packageName,currentContext.packageName,
                        expected.fingerprint,currentContext.fingerprint,
                        expected.designAnchor,currentContext.designAnchor));
            }
            ownerExecutionToken = executionToken;
            visualHash = hash;
            ownerDesignContextCaptured = capturedDesignContext;
            ownerDesignAnchor = capturedDesignContext ? currentDesignAnchor : "";
            runtimeExpectedContext = currentContext;
            runtimeExpectedOwnerExecutionToken = currentContext == null ? "" : executionToken;
            return true;
        });
    }

    /**
     * Production must never mint screenshot evidence while the service is active but
     * the live Canva context disappeared between validation and lease binding. JVM unit
     * tests intentionally have no AccessibilityService instance, so legacy lease tests
     * remain usable without weakening the production rule.
     */
    static boolean mayBindRuntimeEvidence(boolean serviceActive, boolean contextPresent) {
        return !serviceActive || contextPresent;
    }

    /**
     * Last-mile execution policy for teacher-produced visual mutations. In production,
     * a screenshot-grounded action may reach the executor only while a runtime-bound
     * visual evidence context still exists and still matches live Canva. JVM tests run
     * without an AccessibilityService, so they remain isolated from Android runtime state.
     */
    static boolean visualRuntimeEvidenceMayExecute(
            boolean serviceActive,
            boolean evidenceContextPresent,
            boolean evidenceContextCurrent) {
        return !serviceActive || (evidenceContextPresent && evidenceContextCurrent);
    }

    static boolean hasRuntimeExpectedContext() {
        return runtimeExpectedContext != null && !runtimeExpectedOwnerExecutionToken.isEmpty();
    }

    /** Pure ownership policy used by lifecycle cleanup and regression tests. */
    static boolean mayClearRuntimeExpectedContext(String clearingOwner, String runtimeOwner) {
        return clearingOwner != null && !clearingOwner.isEmpty()
                && runtimeOwner != null && clearingOwner.equals(runtimeOwner);
    }

    synchronized String readIfOwnedBy(String executionToken) {
        if (!isOwnedBy(executionToken)) return "";
        if (ownerDesignContextCaptured && !ownerDesignAnchor.equals(currentRuntimeDesignAnchor())) return "";
        if (runtimeExpectedContext != null) {
            if (!executionToken.equals(runtimeExpectedOwnerExecutionToken)) return "";
            if (!isRuntimeDesignContextCurrent()) return "";
        }
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
            if (runtimeExpectedContext != null) {
                if (!executionToken.equals(runtimeExpectedOwnerExecutionToken)) return "";
                if (!isRuntimeDesignContextCurrent()) return "";
            }
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
        RuntimeContext expected = runtimeExpectedContext;
        String expectedOwner = runtimeExpectedOwnerExecutionToken;
        if (expected == null || expectedOwner.isEmpty()) return true;
        if (!TeacherExecutionLease.isGlobalCurrent(expectedOwner)) return false;
        RuntimeContext current = currentRuntimeContext();
        return current != null && executionContextMatches(
                expected.packageName,current.packageName,
                expected.fingerprint,current.fingerprint,
                expected.designAnchor,current.designAnchor);
    }

    static boolean designIdentityMatches(String expected, String current) {
        return expected != null && current != null && expected.trim().equals(current.trim());
    }

    static boolean executionContextMatches(
            String expectedPackage,
            String currentPackage,
            String expectedFingerprint,
            String currentFingerprint,
            String expectedDesignAnchor,
            String currentDesignAnchor) {
        if (expectedPackage == null || currentPackage == null
                || expectedFingerprint == null || currentFingerprint == null
                || expectedDesignAnchor == null || currentDesignAnchor == null) return false;
        return AgentConstants.CANVA_PACKAGE.equals(expectedPackage)
                && expectedPackage.equals(currentPackage)
                && !expectedFingerprint.isEmpty()
                && expectedFingerprint.equals(currentFingerprint)
                && designIdentityMatches(expectedDesignAnchor,currentDesignAnchor);
    }

    private static RuntimeContext currentRuntimeContext() {
        AgentAccessibilityService service = AgentAccessibilityService.INSTANCE;
        if (service == null) return null;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        String packageName = root != null && root.getPackageName() != null
                ? root.getPackageName().toString() : "";
        if (!AgentConstants.CANVA_PACKAGE.equals(packageName) || root == null) return null;
        UiTreeSnapshot snapshot = UiTreeSnapshot.capture(root);
        TaskState state = new TaskStateRepository(service).load();
        String designAnchor = state.designAnchor == null ? null : state.designAnchor.trim();
        return new RuntimeContext(packageName,snapshot.stableFingerprint(),designAnchor);
    }

    private static String currentRuntimeDesignAnchor() {
        RuntimeContext current = currentRuntimeContext();
        return current == null ? null : current.designAnchor;
    }

    /**
     * Explicit lifecycle reset. Runtime context is cleared only when this instance still
     * owns the static context; cleanup from an older service/lease cannot erase evidence
     * bound by a newer execution chain.
     */
    public synchronized void clear() {
        String clearingOwner = ownerExecutionToken;
        ownerExecutionToken = "";
        visualHash = "";
        ownerDesignAnchor = "";
        ownerDesignContextCaptured = false;
        if (mayClearRuntimeExpectedContext(clearingOwner, runtimeExpectedOwnerExecutionToken)) {
            runtimeExpectedContext = null;
            runtimeExpectedOwnerExecutionToken = "";
        }
    }

    synchronized String ownerTokenForTest() { return ownerExecutionToken; }
}
