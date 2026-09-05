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
}
