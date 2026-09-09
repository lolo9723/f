package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;

public final class FinalDoneCommitGuardTest {
    @After public void cleanup() {
        TeacherExecutionLease.invalidateGlobal();
        VerifiedCompletionMemoryHook.clearForTests();
    }

    @Test public void currentLeaseSessionAndVisualProofMayCommitVerifiedSuccessAndStop() {
        String token = TeacherExecutionLease.beginGlobal();
        AtomicBoolean learned = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertTrue(FinalDoneCommitGuard.commitIfCurrent(
                token,
                () -> true,
                () -> true,
                () -> learned.set(true),
                () -> stopped.set(true)
        ));
        assertTrue(learned.get());
        assertTrue(stopped.get());
    }

    @Test public void productionOverloadFailsClosedWithoutRuntimeVisualContext() {
        String token = TeacherExecutionLease.beginGlobal();
        AtomicBoolean learned = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);
        VerifiedCompletionMemoryHook.install(() -> learned.set(true));

        assertFalse(FinalDoneCommitGuard.commitIfCurrent(token, () -> true, () -> stopped.set(true)));
        assertFalse(learned.get());
        assertFalse(stopped.get());
    }

    @Test public void missingVerifiedMemoryHookFailsClosedWithoutStop() {
        String token = TeacherExecutionLease.beginGlobal();
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertFalse(FinalDoneCommitGuard.commitIfCurrent(token, () -> true, () -> stopped.set(true)));
        assertFalse(stopped.get());
    }

    @Test public void staleVisualContextCannotLearnOrStopEvenWithCurrentLeaseAndSession() {
        String token = TeacherExecutionLease.beginGlobal();
        AtomicBoolean learned = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertFalse(FinalDoneCommitGuard.commitIfCurrent(
                token,
                () -> true,
                () -> false,
                () -> learned.set(true),
                () -> stopped.set(true)
        ));
        assertFalse(learned.get());
        assertFalse(stopped.get());
    }

    @Test public void missingOrStaleVisualEvidenceNeverMayCommit() {
        assertFalse(FinalDoneCommitGuard.visualContextMayCommit(false, false));
        assertFalse(FinalDoneCommitGuard.visualContextMayCommit(false, true));
        assertFalse(FinalDoneCommitGuard.visualContextMayCommit(true, false));
        assertTrue(FinalDoneCommitGuard.visualContextMayCommit(true, true));
    }

    @Test public void finalTaskStateRequiresRunningGoalAndBoundDesign() {
        assertTrue(FinalDoneCommitGuard.taskStateMayCommit(TaskState.Mode.RUNNING, "make poster", "Poster A"));
        assertFalse(FinalDoneCommitGuard.taskStateMayCommit(TaskState.Mode.HUMAN_TAKEOVER, "make poster", "Poster A"));
        assertFalse(FinalDoneCommitGuard.taskStateMayCommit(TaskState.Mode.STOPPED, "make poster", "Poster A"));
        assertFalse(FinalDoneCommitGuard.taskStateMayCommit(TaskState.Mode.RUNNING, "", "Poster A"));
        assertFalse(FinalDoneCommitGuard.taskStateMayCommit(TaskState.Mode.RUNNING, "make poster", ""));
        assertFalse(FinalDoneCommitGuard.taskStateMayCommit(TaskState.Mode.RUNNING, "   ", "Poster A"));
        assertFalse(FinalDoneCommitGuard.taskStateMayCommit(TaskState.Mode.RUNNING, "make poster", "   "));
    }

    @Test public void staleLeaseCannotCommitStop() {
        String stale = TeacherExecutionLease.beginGlobal();
        TeacherExecutionLease.beginGlobal();
        AtomicBoolean learned = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertFalse(FinalDoneCommitGuard.commitIfCurrent(
                stale,
                () -> true,
                () -> true,
                () -> learned.set(true),
                () -> stopped.set(true)
        ));
        assertFalse(learned.get());
        assertFalse(stopped.get());
    }

    @Test public void invalidatedLeaseCannotCommitStop() {
        String stale = TeacherExecutionLease.beginGlobal();
        TeacherExecutionLease.invalidateGlobal();
        AtomicBoolean learned = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertFalse(FinalDoneCommitGuard.commitIfCurrent(
                stale,
                () -> true,
                () -> true,
                () -> learned.set(true),
                () -> stopped.set(true)
        ));
        assertFalse(learned.get());
        assertFalse(stopped.get());
    }

    @Test public void staleSessionCannotCommitEvenWithCurrentLease() {
        String token = TeacherExecutionLease.beginGlobal();
        AtomicBoolean learned = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertFalse(FinalDoneCommitGuard.commitIfCurrent(
                token,
                () -> false,
                () -> true,
                () -> learned.set(true),
                () -> stopped.set(true)
        ));
        assertFalse(learned.get());
        assertFalse(stopped.get());
    }

    @Test public void verifiedSuccessAndStopShareExactLeaseAndVisualBoundary() {
        String token = TeacherExecutionLease.beginGlobal();
        AtomicBoolean learned = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertTrue(FinalDoneCommitGuard.commitIfCurrent(
                token,
                () -> true,
                () -> true,
                () -> learned.set(true),
                () -> stopped.set(true)
        ));
        assertTrue(learned.get());
        assertTrue(stopped.get());
    }

    @Test public void failedVisualProofFailsClosedWithoutLearningOrStop() {
        String token = TeacherExecutionLease.beginGlobal();
        AtomicBoolean learned = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertFalse(FinalDoneCommitGuard.commitIfCurrent(
                token,
                () -> true,
                () -> { throw new IllegalStateException("visual proof failed"); },
                () -> learned.set(true),
                () -> stopped.set(true)
        ));
        assertFalse(learned.get());
        assertFalse(stopped.get());
    }

    @Test public void failedVerifiedSuccessPersistenceFailsClosedWithoutCrashOrStop() {
        String token = TeacherExecutionLease.beginGlobal();
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertFalse(FinalDoneCommitGuard.commitIfCurrent(
                token,
                () -> true,
                () -> true,
                () -> { throw new IllegalStateException("memory write failed"); },
                () -> stopped.set(true)
        ));
        assertFalse(stopped.get());
    }

    @Test public void failedStopMutationFailsClosedWithoutCrashingAccessibilityRuntime() {
        String token = TeacherExecutionLease.beginGlobal();
        AtomicBoolean learned = new AtomicBoolean(false);

        assertFalse(FinalDoneCommitGuard.commitIfCurrent(
                token,
                () -> true,
                () -> true,
                () -> learned.set(true),
                () -> { throw new IllegalStateException("stop write failed"); }
        ));
        assertTrue(learned.get());
    }
}
