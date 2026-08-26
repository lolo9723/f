package com.videofabrikasi.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoFactoryScriptTest {
    @Test public void scriptContainsRequiredProductionContract() {
        String s = VideoFactoryScript.build("mektup hikayesi", "p1");
        assertTrue(s.contains("FINAL.mp4"));
        assertTrue(s.contains("LTX-Video"));
        assertTrue(s.contains("PROMPTS = ["));
        assertTrue(s.contains("GENERATING_"));
        assertTrue(s.contains("COMPLETE"));
        assertTrue(s.contains("fallback_video"));
        assertTrue(s.contains("status.json"));
    }

    @Test public void ltxEngineIsPinnedAndUsesDirectoryOutput() {
        String s = VideoFactoryScript.build("test", "p1");
        assertTrue(s.contains("LTX_COMMIT = '4b2d053057623ddd4d0a1d3e9cd28890e9ef487f'"));
        assertTrue(s.contains("output_path=str(scene_dir)"));
        assertTrue(s.contains("scene_dir.glob('*.mp4')"));
        assertTrue(s.contains("prompt_enhancement_words_threshold'] = 0"));
        assertFalse(s.contains("output_path=str(out)"));
    }

    @Test public void userIdeaIsBase64Embedded() {
        String s = VideoFactoryScript.build("a\"\"\"b", "id");
        assertFalse(s.contains("USER_IDEA = a\"\"\"b"));
        assertTrue(s.contains("base64.b64decode"));
    }
}
