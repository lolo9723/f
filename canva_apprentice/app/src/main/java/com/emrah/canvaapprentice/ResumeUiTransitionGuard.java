package com.emrah.canvaapprentice;

/**
 * Fail-closed boundary for user-requested resume callbacks.
 *
 * Durable task-state transitions may throw when SharedPreferences.commit() fails. The overlay must
 * never disappear and make the agent look resumed when that transition was not durably recorded.
 */
public final class ResumeUiTransitionGuard {
    private ResumeUiTransitionGuard() {}

    public interface Transition {
        void run();
    }

    public static boolean runSafely(Transition transition) {
        if (transition == null) return false;
        try {
            transition.run();
            return true;
        } catch (RuntimeException persistenceFailure) {
            return false;
        }
    }
}
