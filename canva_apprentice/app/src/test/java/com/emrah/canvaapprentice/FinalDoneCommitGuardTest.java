package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;

public final class FinalDoneCommitGuardTest {
    @After public void cleanup() {
        TeacherExecutionLease.invalidateGlobal();
    }

    @Test public void currentLeaseAndSessionMayCommitStop() {
        String token = TeacherExecutionLease.beginGlobal();
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertTrue(FinalDoneCommitGuard.commitIfCurrent(token, () -> true, () -> stopped.set(true)));
        assertTrue(stopped.get());
    }

    @Test public void staleLeaseCannotCommitStop() {
        String stale = TeacherExecutionLease.beginGlobal();
        TeacherExecutionLease.beginGlobal();
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertFalse(FinalDoneCommitGuard.commitIfCurrent(stale, () -> true, () -> stopped.set(true)));
        assertFalse(stopped.get());
    }

    @Test public void invalidatedLeaseCannotCommitStop() {
        String stale = TeacherExecutionLease.beginGlobal();
        TeacherExecutionLease.invalidateGlobal();
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertFalse(FinalDoneCommitGuard.commitIfCurrent(stale, () -> true, () -> stopped.set(true)));
        assertFalse(stopped.get());
    }

    @Test public void staleSessionCannotCommitEvenWithCurrentLease() {
        String token = TeacherExecutionLease.beginGlobal();
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertFalse(FinalDoneCommitGuard.commitIfCurrent(token, () -> false, () -> stopped.set(true)));
        assertFalse(stopped.get());
    }

    @Test public void verifiedSuccessAndStopShareExactLeaseBoundary() {
        String token = TeacherExecutionLease.beginGlobal();
        AtomicBoolean learned = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertTrue(FinalDoneCommitGuard.commitIfCurrent(
                token,
                () -> true,
                () -> learned.set(true),
                () -> stopped.set(true)
        ));
        assertTrue(learned.get());
        assertTrue(stopped.get());
    }

    @Test public void staleLeaseCannotLearnVerifiedSuccessOrStop() {
        String stale = TeacherExecutionLease.beginGlobal();
        TeacherExecutionLease.beginGlobal();
        AtomicBoolean learned = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertFalse(FinalDoneCommitGuard.commitIfCurrent(
                stale,
                () -> true,
                () -> learned.set(true),
                () -> stopped.set(true)
        ));
        assertFalse(learned.get());
        assertFalse(stopped.get());
    }

    @Test public void staleSessionCannotLearnVerifiedSuccessOrStop() {
        String token = TeacherExecutionLease.beginGlobal();
        AtomicBoolean learned = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertFalse(FinalDoneCommitGuard.commitIfCurrent(
                token,
                () -> false,
                () -> learned.set(true),
                () -> stopped.set(true)
        ));
        assertFalse(learned.get());
        assertFalse(stopped.get());
    }

    @Test public void failedVerifiedSuccessPersistencePreventsStop() {
        String token = TeacherExecutionLease.beginGlobal();
        AtomicBoolean stopped = new AtomicBoolean(false);
        boolean threw = false;

        try {
            FinalDoneCommitGuard.commitIfCurrent(
                    token,
                    () -> true,
                    () -> { throw new IllegalStateException("memory write failed"); },
                    () -> stopped.set(true)
            );
        } catch (IllegalStateException expected) {
            threw = true;
        }

        assertTrue(threw);
        assertFalse(stopped.get());
    }
}
