package com.emrah.canvaapprentice;

/**
 * Single-owner holder for visual-before evidence used by screenshot-grounded actions.
 * Evidence is bound to the exact teacher execution lease that captured it. A stale
 * action can neither read, replace nor clear evidence belonging to a newer request.
 */
public final class VisualEvidenceLease {
    private String ownerExecutionToken = "";
    private String visualHash = "";

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
    }

    /**
     * Binds evidence only while the supplied execution token still owns the global
     * teacher execution lease. A late screenshot callback from an older request is
     * therefore side-effect free and cannot overwrite newer evidence.
     *
     * For the same live execution token, the first successful visual bind is immutable
     * until consumed/cleared. This prevents duplicate or reordered screenshot callbacks
     * from silently replacing the exact pre-action evidence used for drift verification.
     * Rebinding the identical hash is accepted as an idempotent no-op.
     */
    public synchronized boolean bindIfExecutionCurrent(String executionToken, String hash) {
        if (hash == null || hash.isEmpty()) return false;
        if (!TeacherExecutionLease.isGlobalCurrent(executionToken)) return false;
        if (isOwnedBy(executionToken)) {
            return visualHash.equals(hash);
        }
        ownerExecutionToken = executionToken;
        visualHash = hash;
        return true;
    }

    synchronized String readIfOwnedBy(String executionToken) {
        if (!isOwnedBy(executionToken)) return "";
        return visualHash;
    }

    /** Returns evidence only if ownership and the live global execution lease agree. */
    public synchronized String readIfExecutionCurrent(String executionToken) {
        if (!TeacherExecutionLease.isGlobalCurrent(executionToken)) return "";
        return readIfOwnedBy(executionToken);
    }

    /**
     * Atomically returns and clears evidence for the still-current execution chain.
     * This makes visual-before evidence single-use: duplicate verification callbacks
     * cannot reuse the same screenshot proof after the first consumer has claimed it.
     */
    public synchronized String consumeIfExecutionCurrent(String executionToken) {
        if (!TeacherExecutionLease.isGlobalCurrent(executionToken)) return "";
        if (!isOwnedBy(executionToken)) return "";
        String consumed = visualHash;
        clear();
        return consumed;
    }

    synchronized boolean clearIfOwnedBy(String executionToken) {
        if (!isOwnedBy(executionToken)) return false;
        clear();
        return true;
    }

    /** Clears only the evidence owned by the still-current execution chain. */
    public synchronized boolean clearIfExecutionCurrent(String executionToken) {
        if (!TeacherExecutionLease.isGlobalCurrent(executionToken)) return false;
        return clearIfOwnedBy(executionToken);
    }

    synchronized boolean isOwnedBy(String executionToken) {
        return executionToken != null
                && !executionToken.isEmpty()
                && executionToken.equals(ownerExecutionToken)
                && !visualHash.isEmpty();
    }

    /** Explicit lifecycle reset; never use this from asynchronous request callbacks. */
    public synchronized void clear() {
        ownerExecutionToken = "";
        visualHash = "";
    }

    synchronized String ownerTokenForTest() { return ownerExecutionToken; }
}
