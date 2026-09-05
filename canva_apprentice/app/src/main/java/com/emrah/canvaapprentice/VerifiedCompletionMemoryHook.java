package com.emrah.canvaapprentice;

/**
 * Process-local bridge between the final-DONE guard and durable learning memory.
 *
 * The accessibility service constructs ExperienceMemoryRepository before any teacher
 * cycle can finish. That repository installs the mutation which records a verified
 * completion. FinalDoneCommitGuard requires the hook to be present and executes it
 * inside the exact execution-lease/session boundary immediately before STOP.
 *
 * Missing memory wiring is intentionally fail-closed: a task must not be marked
 * complete when its verified success cannot be persisted.
 */
public final class VerifiedCompletionMemoryHook {
    private static Runnable mutation;

    private VerifiedCompletionMemoryHook() {}

    public static synchronized void install(Runnable verifiedSuccessMutation) {
        mutation = verifiedSuccessMutation;
    }

    public static synchronized Runnable current() {
        return mutation;
    }

    static synchronized void clearForTests() {
        mutation = null;
    }
}
