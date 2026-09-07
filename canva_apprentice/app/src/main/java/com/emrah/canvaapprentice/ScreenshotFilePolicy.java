package com.emrah.canvaapprentice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ScreenshotFilePolicy {
    private static final String PREFIX = "canva_agent_";
    private static final String SUFFIX = ".png";
    private static final Pattern CAPTURE_NAME = Pattern.compile("^canva_agent_([0-9a-f]{16})_([0-9a-f]{32})\\.png$");
    static final long EVIDENCE_RETENTION_MS = 6L * 60L * 60L * 1000L;

    private ScreenshotFilePolicy() {}

    /**
     * Every screenshot filename is bound to the execution lease that was current when capture
     * started. If no lease exists, deliberately emit a non-accepted name so the caller's strict
     * post-write validation deletes the evidence instead of accidentally creating unowned proof.
     */
    static String newCaptureFileName() {
        String leaseToken = TeacherExecutionLease.currentGlobalToken();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String leaseId = leaseIdFor(leaseToken);
        if (leaseId.isEmpty()) return PREFIX + "unleased_" + nonce + SUFFIX;
        return PREFIX + leaseId + "_" + nonce + SUFFIX;
    }

    static boolean isCaptureFileName(String name) {
        return name != null && CAPTURE_NAME.matcher(name).matches();
    }

    /**
     * A strict capture file is usable only by the execution lease that created it. This prevents
     * a stale but otherwise well-formed cache file from being attached to a newer visual request.
     */
    static boolean isCaptureFileForCurrentLease(String name) {
        return isCaptureFileForLease(name, TeacherExecutionLease.currentGlobalToken());
    }

    static boolean isCaptureFileForLease(String name, String leaseToken) {
        if (name == null) return false;
        String expectedLeaseId = leaseIdFor(leaseToken);
        if (expectedLeaseId.isEmpty()) return false;
        Matcher matcher = CAPTURE_NAME.matcher(name);
        return matcher.matches() && expectedLeaseId.equals(matcher.group(1));
    }

    static boolean shouldDeleteExpiredCapture(String name, long lastModifiedMs, long nowMs) {
        if (!isCaptureFileName(name)) return false;
        if (lastModifiedMs <= 0L || nowMs < lastModifiedMs) return false;
        return nowMs - lastModifiedMs >= EVIDENCE_RETENTION_MS;
    }

    private static String leaseIdFor(String leaseToken) {
        if (leaseToken == null || leaseToken.isEmpty()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(leaseToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(16);
            for (int i = 0; i < 8; i++) out.append(String.format(java.util.Locale.US, "%02x", bytes[i]));
            return out.toString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
