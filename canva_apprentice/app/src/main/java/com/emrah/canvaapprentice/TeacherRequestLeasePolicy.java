package com.emrah.canvaapprentice;

/**
 * Defines how teacher transport requests interact with the action execution lease.
 * Structural requests supersede any older action chain. Visual requests are different:
 * their screenshot evidence is captured and bound before ChatGPT is opened, so the
 * transport must preserve that already-current lease instead of rotating it again.
 */
public final class TeacherRequestLeasePolicy {
    private TeacherRequestLeasePolicy() {}

    public static String beginStructuralRequest() {
        return TeacherExecutionLease.beginGlobal();
    }

    /**
     * Returns the lease that already owns the screenshot evidence. Empty means the
     * visual request has no valid execution owner and must fail closed.
     */
    public static String currentVisualRequestLease() {
        return TeacherExecutionLease.currentGlobalToken();
    }
}
