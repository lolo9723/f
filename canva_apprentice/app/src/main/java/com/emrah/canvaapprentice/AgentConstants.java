package com.emrah.canvaapprentice;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public final class AgentConstants {
    private AgentConstants() {}
    public static final String CANVA_PACKAGE = "com.canva.editor";
    public static final String CHATGPT_PACKAGE = "com.openai.chatgpt";
    public static final Set<String> ALLOWED_PACKAGES = new HashSet<>(Arrays.asList(CANVA_PACKAGE, CHATGPT_PACKAGE));
    public static final double SAFE_CLICK_CONFIDENCE = 0.93;
    public static final double SAFE_COORDINATE_CONFIDENCE = 0.985;
}
