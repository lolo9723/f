package com.emrah.canvaapprentice;

import java.util.function.BooleanSupplier;

/**
 * Atomically commits final task completion only while the exact teacher execution
 * lease still owns the chain and the teacher session is still current.
 *
 * Final DONE is special: a visual-evidence read may be valid and then become stale
 * before TaskStateRepository.stop() runs. Keeping the final mutations inside the
 * execution-lease monitor closes that TOCTOU window. A stale chain is side-effect free.
 *
 * Verified-success learning must use the same proof boundary as STOP. Production's
 * three-argument overload therefore requires the memory hook installed by
 * ExperienceMemoryRepository. If the hook is missing or verified-success persistence
 * throws, STOP is not committed and the task remains fail-closed.
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
                verifiedSuccessMutation,
                stopMutation
        );
    }

    public static boolean commitIfCurrent(String executionLeaseToken,
                                          BooleanSupplier sessionStillCurrent,
                                          Runnable verifiedSuccessMutation,
                                          Runnable stopMutation) {
        if (sessionStillCurrent == null || verifiedSuccessMutation == null || stopMutation == null) return false;
        return TeacherExecutionLease.withGlobalCurrent(executionLeaseToken, false, () -> {
            if (!sessionStillCurrent.getAsBoolean()) return false;
            verifiedSuccessMutation.run();
            stopMutation.run();
            return true;
        });
    }
}
