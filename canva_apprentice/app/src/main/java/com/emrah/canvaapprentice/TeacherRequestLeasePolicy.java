package com.emrah.canvaapprentice;

/**
 * Defines how teacher transport requests interact with the action execution lease.
 * Structural and visual requests each receive their own request-scoped execution lease.
 * Transport must preserve the lease carried by the immutable request authority rather
 * than re-reading whichever global lease happens to be current later.
 */
public final class TeacherRequestLeasePolicy {
    private TeacherRequestLeasePolicy() {}

    public static String beginStructuralRequest() {
        return TeacherExecutionLease.beginGlobal();
    }

    /**
     * Visual teacher escalation starts a fresh execution chain. Rotating here revokes
     * the structural action that requested a screenshot, so delayed structural callbacks
     * cannot coexist with or borrow authority from the visual request.
     */
    public static String beginVisualRequest() {
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
