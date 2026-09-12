package com.emrah.canvaapprentice;

/**
 * Fail-closed commit boundary for learned transition memory.
 * A delayed verification may only write if it still belongs to the exact live safe checkpoint
 * of a RUNNING, already-bound Canva design. Rebinding a design clears the safe checkpoint, so
 * stale callbacks from a previous design cannot be persisted under the new design scope.
 */
public final class LearningMemoryWriteContextPolicy {
    private LearningMemoryWriteContextPolicy() {}

    static boolean mayCommit(TaskState.Mode mode,
                             String verifiedBeforeFingerprint,
                             String liveSafeFingerprint,
                             String liveDesignAnchor) {
        if (mode != TaskState.Mode.RUNNING) return false;
        String verified = exactIdentity(verifiedBeforeFingerprint);
        String liveSafe = exactIdentity(liveSafeFingerprint);
        String anchor = exactIdentity(liveDesignAnchor);
        if (!isCanonicalIdentity(verified) || !isCanonicalIdentity(liveSafe) || !isCanonicalIdentity(anchor)) {
            return false;
        }
        return verified.equals(liveSafe);
    }

    /**
     * These values are authority-bearing identities, not display text. Never trim or otherwise
     * normalize them: a padded/corrupted delayed callback must not be allowed to alias the exact
     * live safe checkpoint or bound design and poison learned-memory state.
     */
    private static String exactIdentity(String value) {
        return value == null ? "" : value;
    }

    private static boolean isCanonicalIdentity(String value) {
        if (value == null || value.isEmpty() || value.length() > 512 || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) return false;
        }
        return true;
    }
}
