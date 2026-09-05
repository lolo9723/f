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
 * Verified-success learning must use the same proof boundary as STOP. The overload
 * accepting verifiedSuccessMutation therefore executes that mutation under the exact
 * same lease/session guard, before STOP is committed. If verified-success persistence
 * throws, STOP is not committed and the task remains fail-closed instead of silently
 * learning/finishing from divergent evidence.
 */
public final class FinalDoneCommitGuard {
    private FinalDoneCommitGuard() {}

    public static boolean commitIfCurrent(String executionLeaseToken,
                                          BooleanSupplier sessionStillCurrent,
                                          Runnable stopMutation) {
        return commitIfCurrent(executionLeaseToken, sessionStillCurrent, null, stopMutation);
    }

    public static boolean commitIfCurrent(String executionLeaseToken,
                                          BooleanSupplier sessionStillCurrent,
                                          Runnable verifiedSuccessMutation,
                                          Runnable stopMutation) {
        if (sessionStillCurrent == null || stopMutation == null) return false;
        return TeacherExecutionLease.withGlobalCurrent(executionLeaseToken, false, () -> {
            if (!sessionStillCurrent.getAsBoolean()) return false;
            if (verifiedSuccessMutation != null) verifiedSuccessMutation.run();
            stopMutation.run();
            return true;
        });
    }
}
