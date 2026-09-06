package com.emrah.canvaapprentice;

/**
 * Fail-closed gate for replaying learned navigation evidence.
 *
 * Human takeover/resume deliberately clears lastSafeSnapshotHash. Learned transitions must not
 * influence the teacher again until the current Canva surface has independently refreshed that
 * checkpoint. For a bound design, SafeSnapshotPolicy only refreshes it when the exact anchor is
 * visible, so this also prevents replay from a different/unknown design after DEVAM ET.
 */
public final class MemoryReplayContinuityPolicy {
    private MemoryReplayContinuityPolicy() {}

    public static boolean mayRead(TaskState.Mode mode,
                                  String lastSafeSnapshotHash,
                                  String currentSnapshotHash) {
        if (mode != TaskState.Mode.RUNNING) return false;
        String safe = normalize(lastSafeSnapshotHash);
        String current = normalize(currentSnapshotHash);
        return !safe.isEmpty() && safe.equals(current);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
