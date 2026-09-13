package com.emrah.canvaapprentice;

/**
 * Defines how teacher transport requests interact with the action execution lease.
 * Structural and visual requests each receive their own request-scoped execution lease.
 * Transport must preserve the lease carried by the immutable request authority rather
 * than re-reading whichever global lease happens to be current later.
 */
public final class TeacherRequestLeasePolicy {
    private static volatile String currentVisualExecutionLease = "";

    private TeacherRequestLeasePolicy() {}

    public static String beginStructuralRequest() {
        String lease = TeacherExecutionLease.beginGlobal();
        currentVisualExecutionLease = "";
        return lease;
    }

    /**
     * Visual teacher escalation starts a fresh execution chain. Rotating here revokes
     * the structural action that requested a screenshot, so delayed structural callbacks
     * cannot coexist with or borrow authority from the visual request.
     */
    public static String beginVisualRequest() {
        String lease = TeacherExecutionLease.beginGlobal();
        currentVisualExecutionLease = lease == null ? "" : lease;
        return lease;
    }

    /**
     * Returns only the execution lease owned by this exact fully-grounded structural marker.
     * Empty means the caller has no marker-owned structural execution authority and must fail
     * closed. Never fall back to the globally-current token here: a delayed request could then
     * borrow a newer teacher turn's authority after its own marker became stale.
     */
    public static String currentStructuralRequestLease(String marker) {
        if (marker == null || marker.isEmpty()) return "";
        return CheckpointRequestGuard.currentBoundExecutionLease(marker);
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

    /**
     * Coordinate/visual mutations require provenance from a visual teacher request, not just
     * any current execution lease. This prevents a structural request (or legacy parser caller)
     * from upgrading an ordinary lease into visual authority by setting visualGrounded=true.
     */
    public static boolean visualTransportStillOwns(String expectedExecutionLease) {
        return expectedExecutionLease != null
                && !expectedExecutionLease.isEmpty()
                && expectedExecutionLease.equals(currentVisualExecutionLease)
                && transportStillOwns(expectedExecutionLease);
    }
}
