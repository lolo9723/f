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
        if (!isCanonicalFingerprint(verified)
                || !isCanonicalFingerprint(liveSafe)
                || !isCanonicalDesignIdentity(anchor)) {
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

    /**
     * Snapshot fingerprints are opaque authority tokens. Production fingerprints have no
     * legitimate whitespace, so even matching corrupted values such as "fp 1" must fail closed.
     * Treating fingerprints like display text would let two equally-corrupted delayed values
     * satisfy exact equality and poison learning memory under a non-canonical checkpoint.
     */
    private static boolean isCanonicalFingerprint(String value) {
        if (value == null || value.isEmpty() || value.length() > 512 || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) return false;
        }
        return true;
    }

    /** Design anchors are user-visible names, so ordinary embedded spaces are legitimate. */
    private static boolean isCanonicalDesignIdentity(String value) {
        if (value == null || value.isEmpty() || value.length() > 512 || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return true;
    }
}
