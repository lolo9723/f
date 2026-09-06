package com.emrah.canvaapprentice;

/**
 * Decides whether a UI snapshot is trustworthy enough to become continuity memory.
 * A bound design must never learn Canva home/projects or an unidentified editor as "safe".
 */
public final class SafeSnapshotPolicy {
    private static final int VISUAL_FINGERPRINT_HEX_LENGTH = 16 * 16;

    private SafeSnapshotPolicy() {}

    /**
     * Legacy structural-only checkpoint admission is deliberately disabled. Anchor text by itself
     * can be stale, duplicated, or visible in a shell/navigation surface and therefore cannot prove
     * that the pixels belong to the exact bound Canva design. Callers must migrate to the explicit
     * structural + visual overload below before they may persist continuity authority.
     */
    public static boolean shouldMarkSafe(String boundAnchor,
                                         boolean anchorVisible,
                                         boolean canvaHomeVisible) {
        return false;
    }

    /**
     * A continuity checkpoint is admissible only when exact-design structural evidence and a fresh
     * visual proof from the same observation are both present. The visual proof is represented here
     * as an already-verified same-observation boolean; screenshot lease/fingerprint ownership is
     * enforced by the caller that produces it.
     */
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

    /**
     * Compatibility overload kept fail-closed. Same-session + same-tree + a visual hash are not
     * sufficient by themselves: the recaptured observation must also prove that the exact bound
     * design anchor is visible and that Canva home/projects is not the observed surface. Keeping the
     * old signature compilable but permanently false prevents an overlooked caller from regaining
     * checkpoint authority without these structural facts.
     */
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

    /**
     * Final admission guard for an asynchronous screenshot-backed checkpoint. The screenshot result
     * must still belong to the same teacher session, exact bound design and unchanged structural
     * observation that requested it. The recaptured structural observation must visibly identify the
     * bound design and must not be Canva home/projects. The visual fingerprint must be the complete
     * 16x16 hexadecimal fingerprint emitted by VisualFingerprint.fromFile and must contain more than
     * one luminance bucket. A uniform 256-cell fingerprint is treated as a blank/corrupt capture and
     * cannot create continuity authority even though it is syntactically valid hexadecimal.
     *
     * This guard exists specifically for resume/process-restore races: a screenshot callback that
     * arrives after DEVAM ET rotates the teacher session, after a design rebind, after navigation to
     * Canva home, after the anchor disappears, after the Canva UI tree changes, or with a blank
     * screenshot must never recreate continuity authority from stale or unusable pixels.
     */
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
     * Repository-side fail-closed guard. UI-layer checks are advisory; a stale/asynchronous caller
     * must not be able to recreate runtime continuity after HUMAN_TAKEOVER/STOP, before design
     * binding, or with an empty fingerprint. Keeping this check at the persistence boundary makes
     * last_safe_hash safe even if a future call site forgets the UI policy.
     */
    public static boolean mayPersistCheckpoint(TaskState.Mode mode,
                                               String boundAnchor,
                                               String snapshotHash) {
        if (mode != TaskState.Mode.RUNNING) return false;
        if (boundAnchor == null || boundAnchor.trim().isEmpty()) return false;
        return snapshotHash != null && !snapshotHash.trim().isEmpty();
    }

    /**
     * Runtime restore is fail-closed by mode as well as design ownership. A checkpoint is execution
     * authority, so HUMAN_TAKEOVER, STOPPED and IDLE states must expose no trusted safe hash even if
     * old preferences still contain an otherwise matching anchor/hash pair.
     */
    public static boolean mayRestoreCheckpoint(TaskState.Mode mode,
                                               String currentBoundAnchor,
                                               String checkpointAnchor,
                                               String snapshotHash) {
        if (mode != TaskState.Mode.RUNNING) return false;
        return mayRestoreCheckpoint(currentBoundAnchor, checkpointAnchor, snapshotHash);
    }

    /**
     * Durable checkpoints are design-scoped evidence. A hash without the exact anchor that owned
     * it must fail closed (including legacy/migrated records where checkpointAnchor is absent).
     * This prevents an old editor fingerprint from authorizing continuity after a design rebind or
     * a future call-site bug that forgets to clear the hash.
     */
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
