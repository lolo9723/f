package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VisualRuntimeEvidenceContextTest {
    @Test public void liveServiceRequiresRuntimeEvidenceContext() {
        assertFalse(VisualRequestContextGuard.runtimeEvidenceContextAllowsExecution(true,false));
    }

    @Test public void liveServiceAllowsOnlyWhenRuntimeEvidenceContextExists() {
        assertTrue(VisualRequestContextGuard.runtimeEvidenceContextAllowsExecution(true,true));
    }

    @Test public void missingEvidenceFailsClosedEvenOutsideLiveService() {
        assertFalse(VisualRequestContextGuard.runtimeEvidenceContextAllowsExecution(false,false));
    }

    @Test public void evidencePresenceRemainsNecessaryAndSufficientForContextGate() {
        assertTrue(VisualRequestContextGuard.runtimeEvidenceContextAllowsExecution(false,true));
    }
}
