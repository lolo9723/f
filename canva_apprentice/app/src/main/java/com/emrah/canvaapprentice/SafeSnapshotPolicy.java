package com.emrah.canvaapprentice;

/**
 * Decides whether a UI snapshot is trustworthy enough to become continuity memory.
 * A bound design must never learn Canva home/projects or an unidentified editor as "safe".
 */
public final class SafeSnapshotPolicy {
    private static final int VISUAL_FINGERPRINT_HEX_LENGTH = 16 * 16;

    private SafeSnapshotPolicy() {}

    /**
     * Cheap structural gate for deciding whether production should START the screenshot-backed
     * checkpoint attempt. Returning true here does not persist continuity authority: the repository
     * must still capture fresh pixels and pass mayCommitObservedCheckpoint(...) from the same
     * teacher session, exact bound design and unchanged structural observation.
     */
    public static boolean shouldMarkSafe(String boundAnchor,
                                         boolean anchorVisible,
                                         boolean canvaHomeVisible) {
        if (canvaHomeVisible) return false;
        if (!isCanonicalAnchor(boundAnchor)) return false;
        return anchorVisible;
    }

    public static boolean shouldMarkSafe(String boundAnchor,
                                         boolean anchorVisible,
                                         boolean canvaHomeVisible,
                                         boolean sameObservationVisualVerified) {
        if (canvaHomeVisible) return false;
        if (!isCanonicalAnchor(boundAnchor)) return false;
        if (!anchorVisible) return false;
        return sameObservationVisualVerified;
    }

    /** Compatibility overload kept fail-closed. */
    public static boolean mayCommitObservedCheckpoint(TaskState.Mode mode,
                                                      String currentBoundAnchor,
                                                      String expectedBoundAnchor,
                                                      String currentTeacherSessionId,
                                                      String expectedTeacherSessionId,
                                                      String structuralFingerprint,
                                                      String recapturedFingerprint,
                                                      String visualFingerprint) {
        return false;
    }

    public static boolean mayCommitObservedCheckpoint(TaskState.Mode mode,
                                                      String currentBoundAnchor,
                                                      String expectedBoundAnchor,
                                                      String currentTeacherSessionId,
                                                      String expectedTeacherSessionId,
                                                      String structuralFingerprint,
                                                      String recapturedFingerprint,
                                                      boolean recapturedAnchorVisible,
                                                      boolean recapturedCanvaHomeVisible,
                                                      String visualFingerprint) {
        if (!mayPersistCheckpoint(mode, currentBoundAnchor, structuralFingerprint)) return false;

        if (!isCanonicalAnchor(expectedBoundAnchor)
                || !currentBoundAnchor.equals(expectedBoundAnchor)) return false;
        if (!isCanonicalOpaqueIdentity(currentTeacherSessionId)
                || !isCanonicalOpaqueIdentity(expectedTeacherSessionId)
                || !currentTeacherSessionId.equals(expectedTeacherSessionId)) return false;
        if (!isCanonicalOpaqueIdentity(recapturedFingerprint)
                || !structuralFingerprint.equals(recapturedFingerprint)) return false;
        if (!recapturedAnchorVisible || recapturedCanvaHomeVisible) return false;
        return isUsableVisualFingerprint(visualFingerprint);
    }

    /**
     * Final TOCTOU guard at the persistence boundary. A screenshot-backed checkpoint can be
     * admitted only if the live Canva tree at the exact commit boundary is still the same tree that
     * was recaptured after the screenshot, still visibly identifies the exact bound design, and is
     * still an editor surface rather than Canva home/projects.
     */
    public static boolean commitBoundaryStillMatches(String recapturedFingerprint,
                                                     String liveFingerprint,
                                                     boolean liveAnchorVisible,
                                                     boolean liveCanvaHomeVisible) {
        if (!isCanonicalOpaqueIdentity(recapturedFingerprint)
                || !isCanonicalOpaqueIdentity(liveFingerprint)) return false;
        if (!recapturedFingerprint.equals(liveFingerprint)) return false;
        if (!liveAnchorVisible || liveCanvaHomeVisible) return false;
        return true;
    }

    public static boolean mayPersistCheckpoint(TaskState.Mode mode,
                                               String boundAnchor,
                                               String snapshotHash) {
        if (mode != TaskState.Mode.RUNNING) return false;
        if (!isCanonicalAnchor(boundAnchor)) return false;
        return isCanonicalOpaqueIdentity(snapshotHash);
    }

    public static boolean mayRestoreCheckpoint(TaskState.Mode mode,
                                               String currentBoundAnchor,
                                               String checkpointAnchor,
                                               String snapshotHash) {
        if (mode != TaskState.Mode.RUNNING) return false;
        return mayRestoreCheckpoint(currentBoundAnchor, checkpointAnchor, snapshotHash);
    }

    public static boolean mayRestoreCheckpoint(String currentBoundAnchor,
                                               String checkpointAnchor,
                                               String snapshotHash) {
        if (!isCanonicalAnchor(currentBoundAnchor)
                || !isCanonicalAnchor(checkpointAnchor)
                || !isCanonicalOpaqueIdentity(snapshotHash)) return false;
        return currentBoundAnchor.equals(checkpointAnchor);
    }

    private static boolean isUsableVisualFingerprint(String value) {
        if (!isCanonicalOpaqueIdentity(value)
                || value.length() != VISUAL_FINGERPRINT_HEX_LENGTH) return false;
        char first = value.charAt(0);
        boolean hasDifferentBucket = false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) return false;
            if (value.charAt(i) != first) hasDifferentBucket = true;
        }
        return hasDifferentBucket;
    }

    /**
     * Design anchors are user-visible identity, so internal ordinary spaces are legitimate.
     * Outer whitespace and control characters are not: silently trimming them would let two
     * layers disagree about which exact persisted design owns a continuity checkpoint.
     */
    private static boolean isCanonicalAnchor(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Session IDs and structural/visual fingerprints are opaque authority-bearing identities.
     * They must be exact and must never contain whitespace/control bytes or be normalized into
     * a different identity before comparison.
     */
    private static boolean isCanonicalOpaqueIdentity(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) return false;
        }
        return true;
    }
}
