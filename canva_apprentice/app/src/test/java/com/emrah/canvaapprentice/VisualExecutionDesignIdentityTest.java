package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VisualExecutionDesignIdentityTest {
    @Test public void exactDesignIdentityComparisonIsTrimmedButFailClosed() {
        assertTrue(VisualEvidenceLease.designIdentityMatches(" Design A ", "Design A"));
        assertFalse(VisualEvidenceLease.designIdentityMatches("Design A", "Design B"));
        assertFalse(VisualEvidenceLease.designIdentityMatches("Design A", null));
    }

    @Test public void identicalPixelsCannotAuthorizeStaleDesignContext() {
        String pixels = "0123456789abcdef";
        assertEquals(0.0, VisualFingerprint.distanceForExecutionContext(pixels, pixels, true), 0.0);
        assertEquals(1.0, VisualFingerprint.distanceForExecutionContext(pixels, pixels, false), 0.0);
    }

    @Test public void malformedPixelsStillFailClosedWhenDesignIsCurrent() {
        assertEquals(1.0, VisualFingerprint.distanceForExecutionContext("zz", "zz", true), 0.0);
        assertEquals(1.0, VisualFingerprint.distanceForExecutionContext("abc", "ab", true), 0.0);
    }
}
