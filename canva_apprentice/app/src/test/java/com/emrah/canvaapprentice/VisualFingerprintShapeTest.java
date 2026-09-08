package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class VisualFingerprintShapeTest {
    private static String hex(char c, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(c);
        return out.toString();
    }

    @Test public void identicalProductionShapeIsAccepted() {
        String fingerprint = hex('a', 256);
        assertEquals(0.0, VisualFingerprint.distanceForExecutionContext(fingerprint, fingerprint, true), 0.0);
    }

    @Test public void truncatedSameValueEvidenceFailsClosed() {
        assertEquals(1.0, VisualFingerprint.distanceForExecutionContext("a", "a", true), 0.0);
        assertEquals(1.0, VisualFingerprint.distanceForExecutionContext(hex('a',255), hex('a',255), true), 0.0);
    }

    @Test public void oversizedEvidenceFailsClosed() {
        assertEquals(1.0, VisualFingerprint.distanceForExecutionContext(hex('a',257), hex('a',257), true), 0.0);
    }

    @Test public void nonHexEvidenceFailsClosed() {
        String malformed = hex('a',255) + "z";
        assertEquals(1.0, VisualFingerprint.distanceForExecutionContext(malformed, malformed, true), 0.0);
    }

    @Test public void staleDesignContextFailsClosedEvenWithValidShape() {
        String fingerprint = hex('a',256);
        assertEquals(1.0, VisualFingerprint.distanceForExecutionContext(fingerprint, fingerprint, false), 0.0);
    }
}
