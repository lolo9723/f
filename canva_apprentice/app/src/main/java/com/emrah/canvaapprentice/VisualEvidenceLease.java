package com.emrah.canvaapprentice;

/**
 * Single-owner holder for visual-before evidence used by screenshot-grounded actions.
 * Evidence is bound to the exact teacher execution lease that captured it. A stale
 * action can neither read, replace nor clear evidence belonging to a newer request.
 */
public final class VisualEvidenceLease {
    private String ownerExecutionToken = "";
    private String visualHash = "";

    public synchronized void bind(String executionToken, String hash) {
        if (executionToken == null || executionToken.isEmpty() || hash == null || hash.isEmpty()) {
            clear();
            return;
        }
        ownerExecutionToken = executionToken;
        visualHash = hash;
    }

    /**
     * Binds evidence only while the supplied execution token still owns the global
     * teacher execution lease. A late screenshot callback from an older request is
     * therefore side-effect free and cannot overwrite newer evidence.
     */
    public synchronized boolean bindIfExecutionCurrent(String executionToken, String hash) {
        if (hash == null || hash.isEmpty()) return false;
        if (!TeacherExecutionLease.isGlobalCurrent(executionToken)) return false;
        ownerExecutionToken = executionToken;
        visualHash = hash;
        return true;
    }

    public synchronized String readIfOwnedBy(String executionToken) {
        if (!isOwnedBy(executionToken)) return "";
        return visualHash;
    }

    /** Returns evidence only if ownership and the live global execution lease agree. */
    public synchronized String readIfExecutionCurrent(String executionToken) {
        if (!TeacherExecutionLease.isGlobalCurrent(executionToken)) return "";
        return readIfOwnedBy(executionToken);
    }

    public synchronized boolean clearIfOwnedBy(String executionToken) {
        if (!isOwnedBy(executionToken)) return false;
        clear();
        return true;
    }

    /** Clears only the evidence owned by the still-current execution chain. */
    public synchronized boolean clearIfExecutionCurrent(String executionToken) {
        if (!TeacherExecutionLease.isGlobalCurrent(executionToken)) return false;
        return clearIfOwnedBy(executionToken);
    }

    public synchronized boolean isOwnedBy(String executionToken) {
        return executionToken != null
                && !executionToken.isEmpty()
                && executionToken.equals(ownerExecutionToken)
                && !visualHash.isEmpty();
    }

    public synchronized void clear() {
        ownerExecutionToken = "";
        visualHash = "";
    }

    synchronized String ownerTokenForTest() { return ownerExecutionToken; }
}
