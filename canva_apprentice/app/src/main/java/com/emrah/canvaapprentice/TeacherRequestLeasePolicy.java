package com.emrah.canvaapprentice;

/**
 * Defines how teacher transport requests interact with the action execution lease.
 * The request marker owns structural-lease rotation because that same lease is bound
 * into CheckpointRequestGuard. Transport must preserve that marker-owning lease rather
 * than rotating it a second time after the marker has already been created.
 * Visual requests are similar: their screenshot evidence is captured and bound before
 * ChatGPT is opened, so the transport preserves that already-current lease too.
 */
public final class TeacherRequestLeasePolicy {
    private TeacherRequestLeasePolicy() {}

    public static String beginStructuralRequest() {
        return TeacherExecutionLease.beginGlobal();
    }

    /**
     * Returns the lease already bound to the structural request marker. Empty means the
     * caller has no marker-owned structural execution authority and must fail closed.
     */
    public static String currentStructuralRequestLease() {
        return TeacherExecutionLease.currentGlobalToken();
    }

    /**
     * Returns the lease that already owns the screenshot evidence. Empty means the
     * visual request has no valid execution owner and must fail closed.
     */
    public static String currentVisualRequestLease() {
        return TeacherExecutionLease.currentGlobalToken();
    }

    /**
     * Teacher transport is allowed to touch ChatGPT UI only while the exact execution
     * lease captured when the request was issued is still globally current. Re-reading
     * "whatever token is current now" would let a stale delayed transport borrow a newer
     * request's authority after STOP, human takeover, resume, or another teacher turn.
     */
    public static boolean transportStillOwns(String expectedExecutionLease) {
        return expectedExecutionLease != null
                && !expectedExecutionLease.isEmpty()
                && TeacherExecutionLease.isGlobalCurrent(expectedExecutionLease);
    }
}
