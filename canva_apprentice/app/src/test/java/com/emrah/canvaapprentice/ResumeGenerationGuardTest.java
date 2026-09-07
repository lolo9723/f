package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
