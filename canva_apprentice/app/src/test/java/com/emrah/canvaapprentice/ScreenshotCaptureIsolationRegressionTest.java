package com.emrah.canvaapprentice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenshotCaptureIsolationRegressionTest {
    private static String source(String relativePath) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get("src/main/java/com/emrah/canvaapprentice/", relativePath));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test public void screenshotCaptureNeverUsesSharedMutableStagingFile() throws Exception {
        String service = source("AgentAccessibilityService.java");
        assertFalse(service.contains("canva_agent_last.png"));
        assertTrue(service.contains("ScreenshotFilePolicy.newCaptureFileName()"));
        assertTrue(service.contains("new FileOutputStream(f,false)"));
        assertTrue(service.contains("os.getFD().sync()"));
    }

    @Test public void teacherReceivesExactCapturedFileUri() throws Exception {
        String service = source("AgentAccessibilityService.java");
        String provider = source("ScreenshotProvider.java");
        assertTrue(service.contains("ScreenshotProvider.uriFor(file)"));
        assertFalse(service.contains("ScreenshotProvider.uri()"));
        assertFalse(provider.contains("canva_agent_last.png"));
        assertFalse(provider.contains("renameTo("));
        assertTrue(provider.contains("ScreenshotFilePolicy.isCaptureFileName(file.getName())"));
    }
}
