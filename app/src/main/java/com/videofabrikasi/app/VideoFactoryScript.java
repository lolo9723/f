package com.videofabrikasi.app;

/**
 * Stable entry point used by the Android app and tests.
 *
 * V4 is the active production layer: V3 prompt-language preparation plus
 * semantic/visual scene quality gating. The lower V2/V3 layers remain rollback points.
 */
public final class VideoFactoryScript {
    private VideoFactoryScript() {}

    public static String build(String idea, String projectId) {
        return VideoFactoryScriptV4.build(idea, projectId);
    }
}
