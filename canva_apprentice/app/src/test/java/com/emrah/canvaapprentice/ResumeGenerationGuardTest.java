package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ResumeGenerationGuardTest {
    @Test public void newResumeInvalidatesOlderRetryChain() {
        ResumeGenerationGuard guard = new ResumeGenerationGuard();
        long first = guard.begin();
        assertTrue(guard.isCurrent(first));

        long second = guard.begin();
        assertFalse(guard.isCurrent(first));
        assertTrue(guard.isCurrent(second));
    }

    @Test public void explicitInvalidationKillsCurrentChain() {
        ResumeGenerationGuard guard = new ResumeGenerationGuard();
        long current = guard.begin();
        guard.invalidate();
        assertFalse(guard.isCurrent(current));
    }

    @Test public void multipleStaleGenerationsStayInvalid() {
        ResumeGenerationGuard guard = new ResumeGenerationGuard();
        long first = guard.begin();
        long second = guard.begin();
        long third = guard.begin();
        assertFalse(guard.isCurrent(first));
        assertFalse(guard.isCurrent(second));
        assertTrue(guard.isCurrent(third));
    }

    @Test public void staleGenerationCannotRunGuardedAction() {
        ResumeGenerationGuard guard = new ResumeGenerationGuard();
        long stale = guard.begin();
        long current = guard.begin();
        AtomicInteger effects = new AtomicInteger();

        assertFalse(guard.runIfCurrent(stale, effects::incrementAndGet));
        assertEquals(0, effects.get());
        assertTrue(guard.runIfCurrent(current, effects::incrementAndGet));
        assertEquals(1, effects.get());
    }

    @Test public void consumedGenerationCannotCommitTwice() {
        ResumeGenerationGuard guard = new ResumeGenerationGuard();
        long generation = guard.begin();
        AtomicInteger effects = new AtomicInteger();

        assertTrue(guard.consumeIfCurrent(generation, effects::incrementAndGet));
        assertFalse(guard.consumeIfCurrent(generation, effects::incrementAndGet));
        assertFalse(guard.runIfCurrent(generation, effects::incrementAndGet));
        assertEquals(1, effects.get());
    }

    @Test public void nullActionFailsClosedWithoutConsumingGeneration() {
        ResumeGenerationGuard guard = new ResumeGenerationGuard();
        long generation = guard.begin();

        assertFalse(guard.runIfCurrent(generation, null));
        assertFalse(guard.consumeIfCurrent(generation, null));
        assertTrue(guard.isCurrent(generation));
    }

    @Test public void failedRetryMutationInvalidatesGenerationAndCannotReplay() {
        ResumeGenerationGuard guard = new ResumeGenerationGuard();
        long generation = guard.begin();
        AtomicInteger effects = new AtomicInteger();

        assertFalse(guard.runIfCurrent(generation, () -> {
            effects.incrementAndGet();
            throw new IllegalStateException("partial resume mutation failed");
        }));

        assertEquals(1, effects.get());
        assertFalse(guard.isCurrent(generation));
        assertFalse(guard.runIfCurrent(generation, effects::incrementAndGet));
        assertEquals(1, effects.get());
    }

    @Test public void failedConsumedMutationStaysConsumedAndCannotReplay() {
        ResumeGenerationGuard guard = new ResumeGenerationGuard();
        long generation = guard.begin();
        AtomicInteger effects = new AtomicInteger();

        assertFalse(guard.consumeIfCurrent(generation, () -> {
            effects.incrementAndGet();
            throw new IllegalStateException("terminal resume mutation failed");
        }));

        assertEquals(1, effects.get());
        assertFalse(guard.isCurrent(generation));
        assertFalse(guard.consumeIfCurrent(generation, effects::incrementAndGet));
        assertEquals(1, effects.get());
    }
}
