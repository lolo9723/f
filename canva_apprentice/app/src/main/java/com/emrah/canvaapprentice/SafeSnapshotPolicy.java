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

        // Home/projects is never an editor continuity checkpoint, even before a design is bound.
        // Otherwise that pre-bind hash can survive the later bind and masquerade as editor identity.
        if (canvaHomeVisible) return false;

        // Before binding, a non-home Canva surface may be remembered only as a temporary baseline.
        // bindDesignAnchor() clears this baseline so it can never become proof for the bound design.
        if (anchor.isEmpty()) return true;

        // Once bound, only a screen that visibly proves the same design may refresh the
        // last-safe continuity checkpoint. Unknown/transient editor states remain untrusted.
        return anchorVisible;
    }
}
