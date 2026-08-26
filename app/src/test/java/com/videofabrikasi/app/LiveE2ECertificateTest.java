package com.videofabrikasi.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class LiveE2ECertificateTest {
    private LiveE2ECertificate good() {
        return new LiveE2ECertificate(
                "COMPLETE", true,
                "LTX-Video 2B distilled 0.9.6 T4-FP16 story-v3",
                5, "English", "tr_to_en",
                "previous_scene_last_frame", 0.65,
                "procedural_generic_emotion_sfx_aac", "FINAL.mp4", "");
    }

    @Test public void canonicalV3CertificatePassesOnlyWithFullContract() {
        assertTrue(good().passesCanonicalV3());
        assertEquals("", good().failureReason());
    }

    @Test public void fallbackOrMissingAiNeverPasses() {
        LiveE2ECertificate c = new LiveE2ECertificate(
                "COMPLETE_FALLBACK", false, "fallback renderer", 5,
                "English", "tr_to_en", "previous_scene_last_frame", 0.65,
                "aac", "FINAL.mp4", "model failed");
        assertFalse(c.passesCanonicalV3());
        assertTrue(c.failureReason().contains("ai_ok=false"));
    }

    @Test public void fourScenesCannotBeCertified() {
        LiveE2ECertificate g = good();
        LiveE2ECertificate c = new LiveE2ECertificate(
                g.stage, g.aiOk, g.engine, 4, g.promptLanguage, g.translationMode,
                g.continuity, g.continuityStrength, g.audio, g.finalFile, g.error);
        assertFalse(c.passesCanonicalV3());
        assertEquals("scenes=4", c.failureReason());
    }

    @Test public void untranslatedTurkishPathCannotBeCertified() {
        LiveE2ECertificate g = good();
        LiveE2ECertificate c = new LiveE2ECertificate(
                g.stage, g.aiOk, g.engine, g.scenes, "Turkish", "not_needed",
                g.continuity, g.continuityStrength, g.audio, g.finalFile, g.error);
        assertFalse(c.passesCanonicalV3());
    }

    @Test public void wrongContinuityOrAudioCannotBeCertified() {
        LiveE2ECertificate g = good();
        LiveE2ECertificate c1 = new LiveE2ECertificate(
                g.stage, g.aiOk, g.engine, g.scenes, g.promptLanguage, g.translationMode,
                "none", g.continuityStrength, g.audio, g.finalFile, g.error);
        LiveE2ECertificate c2 = new LiveE2ECertificate(
                g.stage, g.aiOk, g.engine, g.scenes, g.promptLanguage, g.translationMode,
                g.continuity, g.continuityStrength, "none", g.finalFile, g.error);
        assertFalse(c1.passesCanonicalV3());
        assertFalse(c2.passesCanonicalV3());
    }
}
