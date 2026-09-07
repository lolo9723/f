package com.emrah.canvaapprentice;

/** Fail-closed ownership check for DEVAM ET / service-restore resume chains. */
final class ResumeContextPolicy {
    private ResumeContextPolicy() {}

    static boolean isCurrent(TaskState.Mode mode, String currentAnchor, String currentSessionId,
                             String expectedAnchor, String expectedSessionId) {
        if (mode != TaskState.Mode.RUNNING) return false;
        String currentSession=currentSessionId==null?"":currentSessionId.trim();
        String expectedSession=expectedSessionId==null?"":expectedSessionId.trim();
        if(expectedSession.isEmpty() || !expectedSession.equals(currentSession)) return false;
        String current=currentAnchor==null?"":currentAnchor.trim();
        String expected=expectedAnchor==null?"":expectedAnchor.trim();
        return expected.equals(current);
    }
}
