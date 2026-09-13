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
        if (!isCanonicalIdentity(lastSafeSnapshotHash)
                || !isCanonicalIdentity(currentSnapshotHash)) return false;
        return lastSafeSnapshotHash.equals(currentSnapshotHash);
    }

    private static boolean isCanonicalIdentity(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)
                    || Character.isSpaceChar(c)
                    || Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT) return false;
        }
        return true;
    }
}
