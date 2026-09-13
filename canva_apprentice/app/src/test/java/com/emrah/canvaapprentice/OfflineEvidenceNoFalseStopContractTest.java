package com.emrah.canvaapprentice;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OfflineEvidenceNoFalseStopContractTest {
    @Test public void conflictMessageExplicitlyForbidsOfflineOnlyStop() {
        String message = OfflineEvidencePolicy.teacherEvidence(true, Boolean.TRUE);
        assertTrue(message.contains("MUST NOT by itself cause NOOP/HUMAN"));
    }
}
