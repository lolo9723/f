package com.emrah.canvaapprentice;

/**
 * Single-owner holder for visual-before evidence used by screenshot-grounded actions.
 * Evidence is bound to the exact teacher execution lease that captured it. A stale
 * action can neither read nor clear evidence belonging to a newer request.
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

    public synchronized String readIfOwnedBy(String executionToken) {
        if (!isOwnedBy(executionToken)) return "";
        return visualHash;
    }

    public synchronized boolean clearIfOwnedBy(String executionToken) {
        if (!isOwnedBy(executionToken)) return false;
        clear();
        return true;
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
