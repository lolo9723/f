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
        String anchor = normalize(boundAnchor);
        if (canvaHomeVisible) return false;
        if (anchor.isEmpty()) return false;
        return anchorVisible;
    }

    public static boolean shouldMarkSafe(String boundAnchor,
                                         boolean anchorVisible,
                                         boolean canvaHomeVisible,
                                         boolean sameObservationVisualVerified) {
        String anchor = boundAnchor == null ? "" : boundAnchor.trim();
        if (canvaHomeVisible) return false;
        if (anchor.isEmpty()) return false;
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

        String currentAnchor = normalize(currentBoundAnchor);
        String expectedAnchor = normalize(expectedBoundAnchor);
        String currentSession = normalize(currentTeacherSessionId);
        String expectedSession = normalize(expectedTeacherSessionId);
        String before = normalize(structuralFingerprint);
        String after = normalize(recapturedFingerprint);
        String visual = normalize(visualFingerprint);

        if (expectedAnchor.isEmpty() || !currentAnchor.equals(expectedAnchor)) return false;
        if (currentSession.isEmpty() || expectedSession.isEmpty() || !currentSession.equals(expectedSession)) return false;
        if (before.isEmpty() || after.isEmpty() || !before.equals(after)) return false;
        if (!recapturedAnchorVisible || recapturedCanvaHomeVisible) return false;
        return isUsableVisualFingerprint(visual);
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
        String recaptured = normalize(recapturedFingerprint);
        String live = normalize(liveFingerprint);
        if (recaptured.isEmpty() || live.isEmpty()) return false;
        if (!recaptured.equals(live)) return false;
        if (!liveAnchorVisible || liveCanvaHomeVisible) return false;
        return true;
    }

    public static boolean mayPersistCheckpoint(TaskState.Mode mode,
                                               String boundAnchor,
                                               String snapshotHash) {
        if (mode != TaskState.Mode.RUNNING) return false;
        if (boundAnchor == null || boundAnchor.trim().isEmpty()) return false;
        return snapshotHash != null && !snapshotHash.trim().isEmpty();
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
        String current = normalize(currentBoundAnchor);
        String owner = normalize(checkpointAnchor);
        String hash = normalize(snapshotHash);
        if (current.isEmpty() || owner.isEmpty() || hash.isEmpty()) return false;
        return current.equals(owner);
    }

    private static boolean isUsableVisualFingerprint(String value) {
        if (value == null || value.length() != VISUAL_FINGERPRINT_HEX_LENGTH) return false;
        char first = value.charAt(0);
        boolean hasDifferentBucket = false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) return false;
            if (value.charAt(i) != first) hasDifferentBucket = true;
        }
        return hasDifferentBucket;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
