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
        String verified = clean(verifiedBeforeFingerprint);
        String liveSafe = clean(liveSafeFingerprint);
        String anchor = clean(liveDesignAnchor);
        if (verified.isEmpty() || liveSafe.isEmpty() || anchor.isEmpty()) return false;
        return verified.equals(liveSafe);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
