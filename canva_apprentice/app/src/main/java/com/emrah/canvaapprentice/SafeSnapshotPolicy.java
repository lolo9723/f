package com.emrah.canvaapprentice;

/**
 * Decides whether a UI snapshot is trustworthy enough to become continuity memory.
 * A bound design must never learn Canva home/projects or an unidentified editor as "safe".
 */
public final class SafeSnapshotPolicy {
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
     * Durable checkpoints are design-scoped evidence. A hash without the exact anchor that owned
     * it must fail closed (including legacy/migrated records where checkpointAnchor is absent).
     * This prevents an old editor fingerprint from authorizing continuity after a design rebind or
     * a future call-site bug that forgets to clear the hash.
     */
    public static boolean mayRestoreCheckpoint(String currentBoundAnchor,
                                               String checkpointAnchor,
                                               String snapshotHash) {
        String current = currentBoundAnchor == null ? "" : currentBoundAnchor.trim();
        String owner = checkpointAnchor == null ? "" : checkpointAnchor.trim();
        String hash = snapshotHash == null ? "" : snapshotHash.trim();
        if (current.isEmpty() || owner.isEmpty() || hash.isEmpty()) return false;
        return current.equals(owner);
    }
}
