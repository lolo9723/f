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

        // Home/projects is never an editor continuity checkpoint.
        if (canvaHomeVisible) return false;

        // Fail closed before exact design identity is bound. An unbound editor fingerprint has no
        // trustworthy ownership relationship to the user's existing design. Persisting it as a
        // temporary baseline is unnecessary and creates stale-provenance surface across lifecycle
        // races. Once BIND_DESIGN succeeds, the bound design must prove itself explicitly.
        if (anchor.isEmpty()) return false;

        // Once bound, only a screen that visibly proves the same design may refresh the
        // last-safe continuity checkpoint. Unknown/transient editor states remain untrusted.
        return anchorVisible;
    }
}
