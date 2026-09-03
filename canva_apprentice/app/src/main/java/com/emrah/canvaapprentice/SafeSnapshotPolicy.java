package com.emrah.canvaapprentice;

/**
 * Decides whether a UI snapshot is trustworthy enough to become continuity memory.
 * A bound design must never learn Canva home/projects or an unidentified editor as "safe".
 */
public final class SafeSnapshotPolicy {
    private SafeSnapshotPolicy() {}

    public static boolean shouldMarkSafe(String boundAnchor,
                                         boolean anchorVisible,
                                         boolean canvaHomeVisible) {
        String anchor = boundAnchor == null ? "" : boundAnchor.trim();
        if (anchor.isEmpty()) return true;

        // A project card on home is not proof that the bound design is currently open.
        if (canvaHomeVisible) return false;

        // Once bound, only a screen that visibly proves the same design may refresh the
        // last-safe continuity checkpoint. Unknown/transient editor states remain untrusted.
        return anchorVisible;
    }
}
