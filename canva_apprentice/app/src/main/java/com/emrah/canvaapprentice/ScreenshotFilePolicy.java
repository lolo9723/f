package com.emrah.canvaapprentice;

import java.util.UUID;
import java.util.regex.Pattern;

final class ScreenshotFilePolicy {
    private static final String PREFIX = "canva_agent_";
    private static final String SUFFIX = ".png";
    private static final Pattern CAPTURE_NAME = Pattern.compile("^canva_agent_[0-9a-f]{32}\\.png$");

    private ScreenshotFilePolicy() {}

    static String newCaptureFileName() {
        return PREFIX + UUID.randomUUID().toString().replace("-", "") + SUFFIX;
    }

    static boolean isCaptureFileName(String name) {
        return name != null && CAPTURE_NAME.matcher(name).matches();
    }
}
