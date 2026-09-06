package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenshotFilePolicyTest {
    @Test public void generatedCaptureNamesAreUniqueAndStrictlyScoped() {
        String a = ScreenshotFilePolicy.newCaptureFileName();
        String b = ScreenshotFilePolicy.newCaptureFileName();
        assertNotEquals(a, b);
        assertTrue(ScreenshotFilePolicy.isCaptureFileName(a));
        assertTrue(ScreenshotFilePolicy.isCaptureFileName(b));
    }

    @Test public void rejectsLegacySharedAndTraversalLikeNames() {
        assertFalse(ScreenshotFilePolicy.isCaptureFileName("canva_agent_last.png"));
        assertFalse(ScreenshotFilePolicy.isCaptureFileName("../canva_agent_0123456789abcdef0123456789abcdef.png"));
        assertFalse(ScreenshotFilePolicy.isCaptureFileName("canva_agent_0123456789abcdef0123456789abcdeg.png"));
        assertFalse(ScreenshotFilePolicy.isCaptureFileName("canva_agent_0123456789abcdef0123456789abcdef.jpg"));
        assertFalse(ScreenshotFilePolicy.isCaptureFileName("canva_agent_0123456789ABCDEF0123456789ABCDEF.png"));
        assertFalse(ScreenshotFilePolicy.isCaptureFileName(""));
        assertFalse(ScreenshotFilePolicy.isCaptureFileName(null));
    }

    @Test public void onlyStrictOldCaptureEvidenceExpires() {
        String valid = "canva_agent_0123456789abcdef0123456789abcdef.png";
        long now = 10L * ScreenshotFilePolicy.EVIDENCE_RETENTION_MS;
        assertFalse(ScreenshotFilePolicy.shouldDeleteExpiredCapture(valid, now - ScreenshotFilePolicy.EVIDENCE_RETENTION_MS + 1L, now));
        assertTrue(ScreenshotFilePolicy.shouldDeleteExpiredCapture(valid, now - ScreenshotFilePolicy.EVIDENCE_RETENTION_MS, now));
        assertFalse(ScreenshotFilePolicy.shouldDeleteExpiredCapture("canva_agent_last.png", 1L, now));
        assertFalse(ScreenshotFilePolicy.shouldDeleteExpiredCapture(valid, now + 1L, now));
        assertFalse(ScreenshotFilePolicy.shouldDeleteExpiredCapture(valid, 0L, now));
    }
}
