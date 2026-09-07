package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ResumeRuntimeLifecycleTest {
    @Test public void newDevamEtGenerationKillsQueuedRetryFromOlderResume() {
        ResumeGenerationGuard guard = new ResumeGenerationGuard();
        long oldResume = guard.begin();
        long newResume = guard.begin();
        AtomicInteger effects = new AtomicInteger();

        assertFalse(guard.runIfCurrent(oldResume, effects::incrementAndGet));
        assertTrue(guard.runIfCurrent(newResume, effects::incrementAndGet));
        assertEquals(1, effects.get());
    }

    @Test public void successfulResumeConsumesGenerationSoQueuedRetryCannotRestartAgent() {
        ResumeGenerationGuard guard = new ResumeGenerationGuard();
        long resume = guard.begin();
        AtomicInteger effects = new AtomicInteger();

        assertTrue(guard.consumeIfCurrent(resume, effects::incrementAndGet));
        assertFalse(guard.runIfCurrent(resume, effects::incrementAndGet));
        assertEquals(1, effects.get());
    }

    @Test public void stopOrDestroyInvalidationKillsQueuedResumeRetry() {
        ResumeGenerationGuard guard = new ResumeGenerationGuard();
        long resume = guard.begin();
        guard.invalidate();

        assertFalse(guard.runIfCurrent(resume, () -> { throw new AssertionError("stale retry acted"); }));
    }

    @Test public void timeoutConsumePreventsAnyLaterRetryFromActing() {
        ResumeGenerationGuard guard = new ResumeGenerationGuard();
        long resume = guard.begin();
        AtomicInteger humanTakeovers = new AtomicInteger();

        assertTrue(guard.consumeIfCurrent(resume, humanTakeovers::incrementAndGet));
        assertFalse(guard.runIfCurrent(resume, () -> { throw new AssertionError("retry acted after timeout"); }));
        assertEquals(1, humanTakeovers.get());
    }
}
