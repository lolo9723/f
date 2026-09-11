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
            TeacherExecutionLease.invalidateGlobal();
            return invalid(id, fingerprint);
        }
        TeacherRequestAuthority authority =
                new TeacherRequestAuthority(id, marker, lease, fingerprint, true);
        if (!authority.stillOwnsTransport()) {
            TeacherExecutionLease.invalidateGlobal();
            return invalid(id, fingerprint);
        }
        return authority;
    }

    /**
     * Captures an already-created structural request as one immutable authority object.
     * Marker, lease and snapshot are read atomically from CheckpointRequestGuard so delayed
     * transport cannot mix pieces from different checkpoint generations.
     */
    public static TeacherRequestAuthority fromBoundStructural(String marker) {
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
                true
        );
        return authority.stillOwnsTransport()
                ? authority
                : invalid(id, lease.snapshotFingerprint);
    }

    /**
     * Starts a screenshot-backed teacher request with a fresh execution lease and binds the
     * reply marker to the exact screenshot/UI-tree snapshot before transport. Visual replies
     * are parsed through CheckpointRequestGuard too, so leaving this marker unbound would
     * make every otherwise-valid visual reply fail closed and lose its execution authority.
     */
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
            TeacherExecutionLease.invalidateGlobal();
            return invalid(id, fingerprint);
        }
        TeacherRequestAuthority authority = new TeacherRequestAuthority(
                id,
                marker,
                lease,
                fingerprint,
                true
        );
        if (!authority.stillOwnsTransport()) {
            TeacherExecutionLease.invalidateGlobal();
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

    public boolean stillOwnsTransport() {
        if (!isValid()) return false;
        if (!structuralBound) {
            return TeacherRequestLeasePolicy.transportStillOwns(executionLeaseToken);
        }

        // Hold the execution-lease monitor while validating the marker -> lease -> snapshot
        // tuple. Without this atomic boundary a newer teacher request could rotate the global
        // lease after the old token was checked but before the checkpoint tuple was read,
        // briefly allowing delayed transport from the stale request to pass both checks.
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
                exactRequestId(requestId), "", "", exactSnapshotFingerprint(snapshotFingerprint), false);
    }

    private static String requestIdFromMarker(String marker) {
        final String prefix = "CAA1_REPLY_";
        if (!marker.startsWith(prefix) || !marker.endsWith("|") || marker.length() <= prefix.length() + 1) {
            return "";
        }
        String id = marker.substring(prefix.length(), marker.length() - 1);
        return isSafeRequestId(id) ? id : "";
    }

    /**
     * Request ids become part of the line protocol marker, so they must never contain
     * separators, whitespace, control characters or arbitrary teacher-controlled text.
     * UUID-derived ids used by the service fit this deliberately narrow grammar.
     */
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

    /**
     * Snapshot fingerprints are identity-bearing authority, not user-facing text. Never
     * silently trim/canonicalize them and never accept embedded whitespace/control bytes.
     * Production fingerprints are SHA-derived opaque tokens, so whitespace has no legitimate
     * meaning here; accepting it would let different layers disagree about exact identity.
     */
    private static boolean isCanonicalSnapshotFingerprint(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) return false;
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
