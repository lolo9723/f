package com.videofabrikasi.app;

/**
 * Stable entry point used by the Android app and tests.
 *
 * V3 wraps the proven V2 renderer with prompt-language preparation while keeping
 * model-specific details out of the Android UI layer.
 */
public final class VideoFactoryScript {
    private VideoFactoryScript() {}

    public static String build(String idea, String projectId) {
        return VideoFactoryScriptV3.build(idea, projectId);
    }
}
