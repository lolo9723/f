package com.emrah.canvaapprentice;

import java.util.function.BooleanSupplier;

/**
 * Atomically commits final task completion only while the exact teacher execution
 * lease still owns the chain and the teacher session is still current.
 *
 * Final DONE is special: a visual-evidence read may be valid and then become stale
 * before TaskStateRepository.stop() runs. Keeping the stop mutation inside the
 * execution-lease monitor closes that TOCTOU window. A stale chain is side-effect free.
 */
public final class FinalDoneCommitGuard {
    private FinalDoneCommitGuard() {}

    public static boolean commitIfCurrent(String executionLeaseToken,
                                          BooleanSupplier sessionStillCurrent,
                                          Runnable stopMutation) {
        if (sessionStillCurrent == null || stopMutation == null) return false;
        return TeacherExecutionLease.withGlobalCurrent(executionLeaseToken, false, () -> {
            if (!sessionStillCurrent.getAsBoolean()) return false;
            stopMutation.run();
            return true;
        });
    }
}
