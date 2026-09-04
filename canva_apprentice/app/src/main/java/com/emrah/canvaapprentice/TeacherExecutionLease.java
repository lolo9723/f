package com.emrah.canvaapprentice;

import java.util.UUID;

/**
 * Owns the post-teacher execution chain independently from the teacher session.
 * A newer teacher request rotates the lease, so delayed callbacks from an older
 * accepted reply can no longer execute or verify actions in the same session.
 */
public final class TeacherExecutionLease {
    private static final TeacherExecutionLease GLOBAL = new TeacherExecutionLease();
    private String activeToken = "";

    public synchronized String begin() {
        activeToken = UUID.randomUUID().toString();
        return activeToken;
    }

    public synchronized void invalidate() {
        activeToken = "";
    }

    public synchronized boolean isCurrent(String expectedToken) {
        return expectedToken != null
                && !expectedToken.isEmpty()
                && expectedToken.equals(activeToken);
    }

    public synchronized String currentToken() {
        return activeToken;
    }

    /**
     * Completes the chain only if the caller still owns it. This prevents an
     * old verification callback from clearing ownership belonging to a newer chain.
     */
    public synchronized boolean completeIfCurrent(String expectedToken) {
        if (!isCurrent(expectedToken)) return false;
        activeToken = "";
        return true;
    }

    public static String beginGlobal() { return GLOBAL.begin(); }
    public static void invalidateGlobal() { GLOBAL.invalidate(); }
    public static String currentGlobalToken() { return GLOBAL.currentToken(); }
    public static boolean isGlobalCurrent(String token) { return GLOBAL.isCurrent(token); }
}
