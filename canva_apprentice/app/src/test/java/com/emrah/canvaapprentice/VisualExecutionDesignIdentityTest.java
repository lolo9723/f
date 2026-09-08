package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VisualExecutionDesignIdentityTest {
    private static String productionFingerprint(char value) {
        StringBuilder out = new StringBuilder(256);
        for (int i = 0; i < 256; i++) out.append(value);
        return out.toString();
    }

    @Test public void exactDesignIdentityComparisonIsTrimmedButFailClosed() {
        assertTrue(VisualEvidenceLease.designIdentityMatches(" Design A ", "Design A"));
        assertFalse(VisualEvidenceLease.designIdentityMatches("Design A", "Design B"));
        assertFalse(VisualEvidenceLease.designIdentityMatches("Design A", null));
    }

    @Test public void identicalProductionPixelsCannotAuthorizeStaleDesignContext() {
        String pixels = productionFingerprint('a');
        assertEquals(0.0, VisualFingerprint.distanceForExecutionContext(pixels, pixels, true), 0.0);
        assertEquals(1.0, VisualFingerprint.distanceForExecutionContext(pixels, pixels, false), 0.0);
    }

    @Test public void malformedPixelsStillFailClosedWhenDesignIsCurrent() {
        assertEquals(1.0, VisualFingerprint.distanceForExecutionContext("zz", "zz", true), 0.0);
        assertEquals(1.0, VisualFingerprint.distanceForExecutionContext("abc", "ab", true), 0.0);
    }
}
