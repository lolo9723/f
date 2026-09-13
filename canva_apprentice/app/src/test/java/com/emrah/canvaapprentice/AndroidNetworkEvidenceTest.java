package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AndroidNetworkEvidenceTest {
    @Test public void helperFailsClosedToUnknownWithoutServiceInstance() {
        AgentAccessibilityService.INSTANCE = null;
        Boolean state = AndroidNetworkEvidence.currentValidated();
        assertFalse(Boolean.TRUE.equals(state));
        assertTrue(state == null);
    }
}
