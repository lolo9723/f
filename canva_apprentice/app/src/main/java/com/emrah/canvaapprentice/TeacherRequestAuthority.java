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
    private final boolean structuralBound;

    private TeacherRequestAuthority(String requestId, String marker,
                                    String executionLeaseToken, String snapshotFingerprint,
                                    boolean structuralBound) {
        this.requestId = requestId;
        this.marker = marker;
        this.executionLeaseToken = executionLeaseToken;
        this.snapshotFingerprint = snapshotFingerprint;
        this.structuralBound = structuralBound;
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
        return new TeacherRequestAuthority(id, marker, lease, fingerprint, true);
    }

    /**
     * Captures an already-created structural request as one immutable authority object.
     * Marker, lease and snapshot are read atomically from CheckpointRequestGuard so delayed
     * transport cannot mix pieces from different checkpoint generations.
     */
    public static TeacherRequestAuthority fromBoundStructural(String marker) {
        String normalizedMarker = normalize(marker);
        String id = requestIdFromMarker(normalizedMarker);
        if (id.isEmpty()) {
            return invalid("", "");
        }
        CheckpointRequestGuard.RequestLease lease =
                CheckpointRequestGuard.currentBoundRequestLease(normalizedMarker);
        if (!lease.checkpointCurrent
                || lease.executionLeaseToken.isEmpty()
                || lease.snapshotFingerprint.isEmpty()) {
            return invalid(id, lease.snapshotFingerprint);
        }
        return new TeacherRequestAuthority(
                id,
                normalizedMarker,
                lease.executionLeaseToken,
                lease.snapshotFingerprint,
                true
        );
    }

    /**
     * Starts a screenshot-backed teacher request with a fresh execution lease and binds the
     * reply marker to the exact screenshot/UI-tree snapshot before transport. Visual replies
     * are parsed through CheckpointRequestGuard too, so leaving this marker unbound would
     * make every otherwise-valid visual reply fail closed and lose its execution authority.
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
        String marker = "CAA1_REPLY_" + id + "|";
        CheckpointRequestGuard.bind(marker, lease);
        if (!CheckpointRequestGuard.bindSnapshot(marker, fingerprint)) {
            TeacherExecutionLease.invalidateGlobal();
            return invalid(id, fingerprint);
        }
        return new TeacherRequestAuthority(
                id,
                marker,
                lease,
                fingerprint,
                true
        );
    }

    public boolean isValid() {
        return !requestId.isEmpty()
                && !marker.isEmpty()
                && !executionLeaseToken.isEmpty()
                && !snapshotFingerprint.isEmpty();
    }

    public boolean stillOwnsTransport() {
        if (!isValid() || !TeacherRequestLeasePolicy.transportStillOwns(executionLeaseToken)) {
            return false;
        }
        if (!structuralBound) {
            return true;
        }

        // Guard-bound authority is only valid while the exact marker -> lease -> snapshot
        // tuple we captured is still the current checkpoint binding. This prevents a
        // delayed request from remaining executable if the guard entry is replaced,
        // consumed, or advanced while the same global lease happens to remain current.
        CheckpointRequestGuard.RequestLease current =
                CheckpointRequestGuard.currentBoundRequestLease(marker);
        return current.checkpointCurrent
                && executionLeaseToken.equals(current.executionLeaseToken)
                && snapshotFingerprint.equals(current.snapshotFingerprint);
    }

    private static TeacherRequestAuthority invalid(String requestId, String snapshotFingerprint) {
        return new TeacherRequestAuthority(
                normalize(requestId), "", "", normalize(snapshotFingerprint), false);
    }

    private static String requestIdFromMarker(String marker) {
        final String prefix = "CAA1_REPLY_";
        if (!marker.startsWith(prefix) || !marker.endsWith("|") || marker.length() <= prefix.length() + 1) {
            return "";
        }
        return normalize(marker.substring(prefix.length(), marker.length() - 1));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
