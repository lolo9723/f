package com.videofabrikasi.app;

/**
 * Stable entry point used by the Android app and tests.
 *
 * The active production engine lives in VideoFactoryScriptV2 so the engine can evolve
 * without spreading model-specific details through the Android UI layer.
 */
public final class VideoFactoryScript {
    private VideoFactoryScript() {}

    public static String build(String idea, String projectId) {
        return VideoFactoryScriptV2.build(idea, projectId);
    }
}
