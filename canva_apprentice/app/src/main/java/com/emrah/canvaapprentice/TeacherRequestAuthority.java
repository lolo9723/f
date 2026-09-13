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
    private final boolean visualGrounded;

    private TeacherRequestAuthority(String requestId, String marker,
                                    String executionLeaseToken, String snapshotFingerprint,
                                    boolean structuralBound, boolean visualGrounded) {
        this.requestId = requestId;
        this.marker = marker;
        this.executionLeaseToken = executionLeaseToken;
        this.snapshotFingerprint = snapshotFingerprint;
        this.structuralBound = structuralBound;
        this.visualGrounded = visualGrounded;
    }

    public static TeacherRequestAuthority begin(String requestId, String snapshotFingerprint) {
        String id = exactRequestId(requestId);
        String fingerprint = exactSnapshotFingerprint(snapshotFingerprint);
        if (!isSafeRequestId(id) || !isCanonicalSnapshotFingerprint(fingerprint)) {
            return invalid(id, fingerprint);
        }

        String marker = "CAA1_REPLY_" + id + "|";
        String lease = TeacherRequestLeasePolicy.beginStructuralRequest();
        if (lease == null || lease.isEmpty()) {
            return invalid(id, fingerprint);
        }

        if (!CheckpointRequestGuard.bindFullyGrounded(marker, lease, fingerprint)) {
            TeacherExecutionLease.invalidateGlobalIfCurrent(lease);
            return invalid(id, fingerprint);
        }
        TeacherRequestAuthority authority =
                new TeacherRequestAuthority(id, marker, lease, fingerprint, true, false);
        if (!authority.stillOwnsTransport()) {
            TeacherExecutionLease.invalidateGlobalIfCurrent(lease);
            return invalid(id, fingerprint);
        }
        return authority;
    }

    /** Legacy package-local compatibility only. Production must carry immutable authority directly. */
    @Deprecated
    static TeacherRequestAuthority fromBoundStructural(String marker) {
        String exactMarker = marker == null ? "" : marker;
        String id = requestIdFromMarker(exactMarker);
        if (!isSafeRequestId(id)) {
            return invalid("", "");
        }
        CheckpointRequestGuard.RequestLease lease =
                CheckpointRequestGuard.currentBoundRequestLease(exactMarker);
        if (!lease.checkpointCurrent
                || lease.executionLeaseToken.isEmpty()
                || !isCanonicalSnapshotFingerprint(lease.snapshotFingerprint)) {
            return invalid(id, lease.snapshotFingerprint);
        }
        TeacherRequestAuthority authority = new TeacherRequestAuthority(
                id,
                exactMarker,
                lease.executionLeaseToken,
                lease.snapshotFingerprint,
                true,
                false
        );
        return authority.stillOwnsTransport()
                ? authority
                : invalid(id, lease.snapshotFingerprint);
    }

    public static TeacherRequestAuthority beginVisual(String requestId, String snapshotFingerprint) {
        String id = exactRequestId(requestId);
        String fingerprint = exactSnapshotFingerprint(snapshotFingerprint);
        if (!isSafeRequestId(id) || !isCanonicalSnapshotFingerprint(fingerprint)) {
            return invalid(id, fingerprint);
        }
        String lease = TeacherRequestLeasePolicy.beginVisualRequest();
        if (lease == null || lease.isEmpty()) {
            return invalid(id, fingerprint);
        }
        String marker = "CAA1_REPLY_" + id + "|";
        if (!CheckpointRequestGuard.bindFullyGrounded(marker, lease, fingerprint)) {
            TeacherExecutionLease.invalidateGlobalIfCurrent(lease);
            return invalid(id, fingerprint);
        }
        TeacherRequestAuthority authority = new TeacherRequestAuthority(
                id,
                marker,
                lease,
                fingerprint,
                true,
                true
        );
        if (!authority.stillOwnsTransport()) {
            TeacherExecutionLease.invalidateGlobalIfCurrent(lease);
            return invalid(id, fingerprint);
        }
        return authority;
    }

    public boolean isValid() {
        return isSafeRequestId(requestId)
                && !marker.isEmpty()
                && marker.equals("CAA1_REPLY_" + requestId + "|")
                && !executionLeaseToken.isEmpty()
                && isCanonicalSnapshotFingerprint(snapshotFingerprint);
    }

    public boolean isVisualGrounded() {
        return isValid() && visualGrounded;
    }

    public boolean stillOwnsTransport() {
        if (!isValid()) return false;
        if (!structuralBound) {
            return TeacherRequestLeasePolicy.transportStillOwns(executionLeaseToken);
        }
        return TeacherExecutionLease.withGlobalCurrent(executionLeaseToken, false, () -> {
            CheckpointRequestGuard.RequestLease current =
                    CheckpointRequestGuard.currentBoundRequestLease(marker);
            return current.checkpointCurrent
                    && executionLeaseToken.equals(current.executionLeaseToken)
                    && snapshotFingerprint.equals(current.snapshotFingerprint);
        });
    }

    private static TeacherRequestAuthority invalid(String requestId, String snapshotFingerprint) {
        return new TeacherRequestAuthority(
                exactRequestId(requestId), "", "", exactSnapshotFingerprint(snapshotFingerprint), false, false);
    }

    private static String requestIdFromMarker(String marker) {
        final String prefix = "CAA1_REPLY_";
        if (!marker.startsWith(prefix) || !marker.endsWith("|") || marker.length() <= prefix.length() + 1) {
            return "";
        }
        String id = marker.substring(prefix.length(), marker.length() - 1);
        return isSafeRequestId(id) ? id : "";
    }

    private static boolean isSafeRequestId(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!safe) return false;
        }
        return true;
    }

    private static boolean isCanonicalSnapshotFingerprint(String value) {
        if (value == null || value.isEmpty() || value.length() > 256 || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)
                    || Character.isSpaceChar(c)
                    || Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT) return false;
        }
        return true;
    }

    private static String exactRequestId(String value) {
        return value == null ? "" : value;
    }

    private static String exactSnapshotFingerprint(String value) {
        return value == null ? "" : value;
    }
}