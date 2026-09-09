package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VisualExecutionBoundaryTest {
    private static String solidHash(char nibble) {
        StringBuilder out = new StringBuilder(256);
        for (int i = 0; i < 256; i++) out.append(nibble);
        return out.toString();
    }

    @Test public void pixelMeasurementDoesNotImplyExecutionAuthorization() {
        String identical = solidHash('4');
        double drift = VisualFingerprint.distanceForExecutionContext(identical, identical, true);
        assertEquals(0.0, drift, 0.0);

        assertFalse(VisualRequestContextGuard.currentExecutionAllows(
                drift,0.0100,false,false,
                AgentConstants.CANVA_PACKAGE,"fp-1","Design A",true,false));
    }

    @Test public void explicitExecutionBoundaryAllowsOnlyCurrentBoundEditorContext() {
        assertTrue(VisualRequestContextGuard.currentExecutionAllows(
                0.0000,0.0100,true,true,
                AgentConstants.CANVA_PACKAGE,"fp-1","Design A",true,false));
        assertFalse(VisualRequestContextGuard.currentExecutionAllows(
                0.0000,0.0100,true,true,
                AgentConstants.CANVA_PACKAGE,"fp-1","",false,false));
        assertFalse(VisualRequestContextGuard.currentExecutionAllows(
                0.0000,0.0100,true,true,
                AgentConstants.CANVA_PACKAGE,"fp-1","Design A",true,true));
    }
}
