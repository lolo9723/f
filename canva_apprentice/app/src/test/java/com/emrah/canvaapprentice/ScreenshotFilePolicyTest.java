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
        assertFalse(ScreenshotFilePolicy.isCaptureFileName(""));
        assertFalse(ScreenshotFilePolicy.isCaptureFileName(null));
    }
}
