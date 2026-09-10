package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ResumeUiTransitionGuardTest {
    @Test public void successfulResumeCallbackIsAccepted() {
        assertTrue(ResumeUiTransitionGuard.runSafely(() -> {}));
    }

    @Test public void durablePersistenceFailureFailsClosed() {
        assertFalse(ResumeUiTransitionGuard.runSafely(() -> {
            throw new IllegalStateException("Durable task resume persistence failed");
        }));
    }

    @Test public void missingCallbackFailsClosed() {
        assertFalse(ResumeUiTransitionGuard.runSafely(null));
    }
}
