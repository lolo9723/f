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

    @Test public void teacherReceivesExactCapturedFileUriAndProviderRechecksLiveLease() throws Exception {
        String service = source("AgentAccessibilityService.java");
        String provider = source("ScreenshotProvider.java");
        assertTrue(service.contains("ScreenshotProvider.uriFor(file)"));
        assertFalse(service.contains("ScreenshotProvider.uri()"));
        assertFalse(provider.contains("canva_agent_last.png"));
        assertFalse(provider.contains("renameTo("));
        assertTrue(provider.contains("ScreenshotFilePolicy.isCaptureFileForCurrentLease(file.getName())"));
        assertTrue(provider.contains("ScreenshotFilePolicy.isCaptureFileForLease(fileName, leaseToken)"));
        assertFalse(provider.contains("return ScreenshotFilePolicy.isCaptureFileName(uri.getLastPathSegment())"));
    }

    @Test public void providerMetadataAndFileOpenUseSameAtomicLeaseMonitor() throws Exception {
        String provider = source("ScreenshotProvider.java");
        assertTrue(provider.contains("@Override public String getType(Uri uri)"));
        assertTrue(provider.contains("@Override public Cursor query(Uri uri"));
        assertTrue(provider.contains("TeacherExecutionLease.withGlobalCurrent("));
        assertTrue(provider.contains("TeacherExecutionLease.withGlobalCurrentChecked("));
        assertTrue(provider.contains("if (!ScreenshotFilePolicy.isCaptureFileForLease(fileName, leaseToken)) return null;"));
        assertFalse(provider.contains("private boolean isAllowed(Uri uri)"));
        assertTrue(provider.contains("private boolean hasAllowedShape(Uri uri)"));
    }

    @Test public void staleOrMissingEvidenceDoesNotLeakMetadata() throws Exception {
        String provider = source("ScreenshotProvider.java");
        assertTrue(provider.contains("if (!file.exists() || !file.isFile()) return null;"));
        assertFalse(provider.contains("file.exists() ? file.length() : 0L"));
        assertTrue(provider.contains("else if (OpenableColumns.SIZE.equals(col)) row.add(file.length());"));
    }
}
