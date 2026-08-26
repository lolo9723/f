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
    }
    @Test public void userIdeaIsBase64Embedded() {
        String s = VideoFactoryScript.build("a\"\"\"b", "id");
        assertFalse(s.contains("USER_IDEA = a\"\"\"b"));
        assertTrue(s.contains("base64.b64decode"));
    }
}
