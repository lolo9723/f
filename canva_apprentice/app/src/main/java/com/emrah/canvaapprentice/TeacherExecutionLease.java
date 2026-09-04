package com.emrah.canvaapprentice;

import java.util.UUID;
import java.util.function.Supplier;

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

    /**
     * Runs a state mutation while holding the same monitor that guards the global
     * execution token. This closes the check-then-act race where a caller could
     * observe a current token, lose ownership to a newer teacher request, and then
     * still mutate visual/runtime state using the stale result.
     */
    public static <T> T withGlobalCurrent(String expectedToken, T staleValue, Supplier<T> operation) {
        synchronized (GLOBAL) {
            if (!GLOBAL.isCurrent(expectedToken)) return staleValue;
            return operation.get();
        }
    }

    public static String beginGlobal() { return GLOBAL.begin(); }
    public static void invalidateGlobal() { GLOBAL.invalidate(); }
    public static String currentGlobalToken() { return GLOBAL.currentToken(); }
    public static boolean isGlobalCurrent(String token) { return GLOBAL.isCurrent(token); }
}
