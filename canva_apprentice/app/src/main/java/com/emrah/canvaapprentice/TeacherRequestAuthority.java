package com.emrah.canvaapprentice;

/**
 * Immutable authority for one teacher request. The protocol marker, execution lease and
 * exact teacher-visible snapshot are created/bound together before transport starts.
 * Delayed transport must carry this exact authority instead of re-reading whichever
 * global lease happens to be current later.
 */
public final class TeacherRequestAuthority {
    public final String requestId;
    public final String marker;
    public final String executionLeaseToken;
    public final String snapshotFingerprint;

    private TeacherRequestAuthority(String requestId, String marker,
                                    String executionLeaseToken, String snapshotFingerprint) {
        this.requestId = requestId;
        this.marker = marker;
        this.executionLeaseToken = executionLeaseToken;
        this.snapshotFingerprint = snapshotFingerprint;
    }

    public static TeacherRequestAuthority begin(String requestId, String snapshotFingerprint) {
        String id = normalize(requestId);
        String fingerprint = normalize(snapshotFingerprint);
        if (id.isEmpty() || fingerprint.isEmpty()) {
            return invalid(id, fingerprint);
        }

        String marker = "CAA1_REPLY_" + id + "|";
        String lease = TeacherRequestLeasePolicy.beginStructuralRequest();
        if (lease == null || lease.isEmpty()) {
            return invalid(id, fingerprint);
        }

        CheckpointRequestGuard.bind(marker, lease);
        if (!CheckpointRequestGuard.bindSnapshot(marker, fingerprint)) {
            TeacherExecutionLease.invalidateGlobal();
            return invalid(id, fingerprint);
        }
        return new TeacherRequestAuthority(id, marker, lease, fingerprint);
    }

    /**
     * Starts a screenshot-backed teacher request with a fresh execution lease. Unlike a
     * structural request, visual authority is not stored in CheckpointRequestGuard: the
     * screenshot evidence itself is bound to this exact lease by the service before the
     * request is transported to ChatGPT.
     */
    public static TeacherRequestAuthority beginVisual(String requestId, String snapshotFingerprint) {
        String id = normalize(requestId);
        String fingerprint = normalize(snapshotFingerprint);
        if (id.isEmpty() || fingerprint.isEmpty()) {
            return invalid(id, fingerprint);
        }
        String lease = TeacherRequestLeasePolicy.beginVisualRequest();
        if (lease == null || lease.isEmpty()) {
            return invalid(id, fingerprint);
        }
        return new TeacherRequestAuthority(
                id,
                "CAA1_REPLY_" + id + "|",
                lease,
                fingerprint
        );
    }

    public boolean isValid() {
        return !requestId.isEmpty()
                && !marker.isEmpty()
                && !executionLeaseToken.isEmpty()
                && !snapshotFingerprint.isEmpty();
    }

    public boolean stillOwnsTransport() {
        return isValid() && TeacherRequestLeasePolicy.transportStillOwns(executionLeaseToken);
    }

    private static TeacherRequestAuthority invalid(String requestId, String snapshotFingerprint) {
        return new TeacherRequestAuthority(
                normalize(requestId), "", "", normalize(snapshotFingerprint));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
