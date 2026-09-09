package com.emrah.canvaapprentice;

import java.util.function.BooleanSupplier;

/**
 * Atomically commits final task completion only while the exact teacher execution
 * lease still owns the chain, the teacher session is still current, and the final
 * screenshot-grounded Canva context is still the one approved by the teacher.
 *
 * Final DONE is special: a visual-evidence read may be valid and then become stale
 * before TaskStateRepository.stop() runs. Keeping the final mutations inside the
 * execution-lease monitor closes the lease/session TOCTOU window, while the visual
 * context check closes the remaining stale-screen/design handoff at commit time.
 * A stale chain is side-effect free.
 *
 * Verified-success learning must use the same proof boundary as STOP. Production's
 * three-argument overload therefore requires both the memory hook installed by
 * ExperienceMemoryRepository and a live runtime visual-evidence context owned by
 * the current execution. If either proof is missing or stale, STOP is not committed.
 * Runtime failures in any proof or mutation are contained so final-QA persistence
 * cannot crash the accessibility service and strand runtime ownership.
 */
public final class FinalDoneCommitGuard {
    private FinalDoneCommitGuard() {}

    public static boolean commitIfCurrent(String executionLeaseToken,
                                          BooleanSupplier sessionStillCurrent,
                                          Runnable stopMutation) {
        Runnable verifiedSuccessMutation = VerifiedCompletionMemoryHook.current();
        if (verifiedSuccessMutation == null) return false;
        return commitIfCurrent(
                executionLeaseToken,
                sessionStillCurrent,
                FinalDoneCommitGuard::runtimeVisualContextStillCurrent,
                verifiedSuccessMutation,
                stopMutation
        );
    }

    public static boolean commitIfCurrent(String executionLeaseToken,
                                          BooleanSupplier sessionStillCurrent,
                                          Runnable verifiedSuccessMutation,
                                          Runnable stopMutation) {
        return commitIfCurrent(
                executionLeaseToken,
                sessionStillCurrent,
                FinalDoneCommitGuard::runtimeVisualContextStillCurrent,
                verifiedSuccessMutation,
                stopMutation
        );
    }

    static boolean commitIfCurrent(String executionLeaseToken,
                                   BooleanSupplier sessionStillCurrent,
                                   BooleanSupplier finalVisualContextStillCurrent,
                                   Runnable verifiedSuccessMutation,
                                   Runnable stopMutation) {
        if (sessionStillCurrent == null || finalVisualContextStillCurrent == null
                || verifiedSuccessMutation == null || stopMutation == null) return false;
        try {
            return TeacherExecutionLease.withGlobalCurrent(executionLeaseToken, false, () -> {
                if (!sessionStillCurrent.getAsBoolean()) return false;
                if (!finalVisualContextStillCurrent.getAsBoolean()) return false;
                verifiedSuccessMutation.run();
                stopMutation.run();
                return true;
            });
        } catch (RuntimeException | Error failure) {
            // Final completion is safety-critical. A proof/persistence/runtime failure
            // must not escape into AccessibilityService. Returning false keeps the
            // caller fail-closed; STOP is never attempted when an earlier proof fails.
            return false;
        }
    }

    static boolean visualContextMayCommit(boolean evidenceContextPresent, boolean evidenceContextCurrent) {
        return evidenceContextPresent && evidenceContextCurrent;
    }

    private static boolean runtimeVisualContextStillCurrent() {
        boolean present = VisualEvidenceLease.hasRuntimeExpectedContext();
        boolean current = present && VisualEvidenceLease.isRuntimeDesignContextCurrent();
        return visualContextMayCommit(present, current);
    }
}
