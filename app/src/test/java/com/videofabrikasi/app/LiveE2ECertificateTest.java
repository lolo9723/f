package com.videofabrikasi.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class LiveE2ECertificateTest {
    private LiveE2ECertificate good() {
        return new LiveE2ECertificate(
                "COMPLETE", true,
                "LTX-Video 2B distilled 0.9.6 T4-FP16 story-v4",
                5, "English", "tr_to_en",
                "previous_scene_last_frame", 0.65,
                "procedural_generic_emotion_sfx_aac", "FINAL.mp4",
                "siglip_semantic_plus_visual_integrity", 5, "");
    }

    @Test public void canonicalV4CertificatePassesOnlyWithFullContract() {
        assertTrue(good().passesCanonicalV4());
        assertEquals("", good().failureReason());
    }

    @Test public void fallbackOrMissingAiNeverPasses() {
        LiveE2ECertificate c = new LiveE2ECertificate(
                "COMPLETE_FALLBACK", false, "fallback renderer", 5,
                "English", "tr_to_en", "previous_scene_last_frame", 0.65,
                "aac", "FINAL.mp4",
                "siglip_semantic_plus_visual_integrity", 5, "model failed");
        assertFalse(c.passesCanonicalV4());
        assertTrue(c.failureReason().contains("ai_ok=false"));
    }

    @Test public void fourScenesCannotBeCertified() {
        LiveE2ECertificate g = good();
        LiveE2ECertificate c = new LiveE2ECertificate(
                g.stage, g.aiOk, g.engine, 4, g.promptLanguage, g.translationMode,
                g.continuity, g.continuityStrength, g.audio, g.finalFile,
                g.qualityGate, g.qualityPassedScenes, g.error);
        assertFalse(c.passesCanonicalV4());
        assertEquals("scenes=4", c.failureReason());
    }

    @Test public void untranslatedTurkishPathCannotBeCertified() {
        LiveE2ECertificate g = good();
        LiveE2ECertificate c = new LiveE2ECertificate(
                g.stage, g.aiOk, g.engine, g.scenes, "Turkish", "not_needed",
                g.continuity, g.continuityStrength, g.audio, g.finalFile,
                g.qualityGate, g.qualityPassedScenes, g.error);
        assertFalse(c.passesCanonicalV4());
    }

    @Test public void wrongContinuityOrAudioCannotBeCertified() {
        LiveE2ECertificate g = good();
        LiveE2ECertificate c1 = new LiveE2ECertificate(
                g.stage, g.aiOk, g.engine, g.scenes, g.promptLanguage, g.translationMode,
                "none", g.continuityStrength, g.audio, g.finalFile,
                g.qualityGate, g.qualityPassedScenes, g.error);
        LiveE2ECertificate c2 = new LiveE2ECertificate(
                g.stage, g.aiOk, g.engine, g.scenes, g.promptLanguage, g.translationMode,
                g.continuity, g.continuityStrength, "none", g.finalFile,
                g.qualityGate, g.qualityPassedScenes, g.error);
        assertFalse(c1.passesCanonicalV4());
        assertFalse(c2.passesCanonicalV4());
    }

    @Test public void missingQualityGateOrOneFailedSceneCannotBeCertified() {
        LiveE2ECertificate g = good();
        LiveE2ECertificate noGate = new LiveE2ECertificate(
                g.stage, g.aiOk, g.engine, g.scenes, g.promptLanguage, g.translationMode,
                g.continuity, g.continuityStrength, g.audio, g.finalFile,
                "", 5, g.error);
        LiveE2ECertificate oneFailed = new LiveE2ECertificate(
                g.stage, g.aiOk, g.engine, g.scenes, g.promptLanguage, g.translationMode,
                g.continuity, g.continuityStrength, g.audio, g.finalFile,
                g.qualityGate, 4, g.error);
        assertFalse(noGate.passesCanonicalV4());
        assertFalse(oneFailed.passesCanonicalV4());
        assertEquals("quality_passed_scenes=4", oneFailed.failureReason());
    }
}
