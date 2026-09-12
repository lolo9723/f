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
 * STOP is intentionally committed before verified-success learning. Durable task
 * ownership is authoritative; learning memory is secondary. If STOP throws/fails,
 * verified success must never be recorded for a task that can still continue. If
 * learning persistence fails after STOP, the task remains safely stopped and the
 * guard reports failure without reopening or re-running it.
 *
 * Production's three-argument overload requires both the memory hook installed by
 * ExperienceMemoryRepository and a live runtime visual-evidence context owned by
 * the current execution. The live task itself must also still be RUNNING with a
 * non-empty goal and bound design at the exact commit boundary. Runtime failures in
 * any proof or mutation are contained so final-QA persistence cannot crash the
 * accessibility service or create a false learned-success-before-stop state.
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
                FinalDoneCommitGuard::runtimeFinalContextStillCurrent,
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
                FinalDoneCommitGuard::runtimeFinalContextStillCurrent,
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

                // Authoritative runtime state always precedes advisory learning state.
                // A task must never learn a verified final success while STOP itself
                // failed and the agent may therefore still be RUNNING.
                stopMutation.run();
                verifiedSuccessMutation.run();

                // A successful final commit is terminal for this exact execution chain.
                // Consume the lease under the same re-entrant monitor before returning
                // success so delayed duplicate DONE callbacks can never re-run STOP or
                // verified-success learning with an already-finished authority.
                if (!TeacherExecutionLease.completeGlobalIfCurrent(executionLeaseToken)) {
                    return false;
                }
                return true;
            });
        } catch (RuntimeException | Error failure) {
            // Final completion is safety-critical. A proof/persistence/runtime failure
            // must not escape into AccessibilityService. If STOP failed, learning was
            // never attempted. If learning failed after STOP, STOP remains authoritative.
            return false;
        }
    }

    static boolean visualContextMayCommit(boolean evidenceContextPresent, boolean evidenceContextCurrent) {
        return evidenceContextPresent && evidenceContextCurrent;
    }

    static boolean taskStateMayCommit(TaskState.Mode mode, String goal, String designAnchor) {
        return mode == TaskState.Mode.RUNNING
                && goal != null && !goal.trim().isEmpty()
                && designAnchor != null && !designAnchor.trim().isEmpty();
    }

    private static boolean runtimeFinalContextStillCurrent() {
        boolean present = VisualEvidenceLease.hasRuntimeExpectedContext();
        boolean current = present && VisualEvidenceLease.isRuntimeDesignContextCurrent();
        if (!visualContextMayCommit(present, current)) return false;

        AgentAccessibilityService service = AgentAccessibilityService.INSTANCE;
        if (service == null) return false;
        TaskState live = new TaskStateRepository(service).load();
        return taskStateMayCommit(live.mode, live.goal, live.designAnchor);
    }
}
