package com.videofabrikasi.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** Immutable proof extracted from Kaggle status.json for the canonical live E2E run. */
final class LiveE2ECertificate {
    final String stage;
    final boolean aiOk;
    final String engine;
    final int scenes;
    final String promptLanguage;
    final String translationMode;
    final String continuity;
    final double continuityStrength;
    final String audio;
    final String finalFile;
    final String qualityGate;
    final int qualityPassedScenes;
    final int qualityTotalScenes;
    final String error;

    LiveE2ECertificate(String stage, boolean aiOk, String engine, int scenes,
                       String promptLanguage, String translationMode, String continuity,
                       double continuityStrength, String audio, String finalFile, String error) {
        this(stage, aiOk, engine, scenes, promptLanguage, translationMode, continuity,
                continuityStrength, audio, finalFile, "", 0, error);
    }

    LiveE2ECertificate(String stage, boolean aiOk, String engine, int scenes,
                       String promptLanguage, String translationMode, String continuity,
                       double continuityStrength, String audio, String finalFile,
                       String qualityGate, int qualityPassedScenes, String error) {
        this(stage, aiOk, engine, scenes, promptLanguage, translationMode, continuity,
                continuityStrength, audio, finalFile, qualityGate,
                qualityPassedScenes, qualityPassedScenes, error);
    }

    LiveE2ECertificate(String stage, boolean aiOk, String engine, int scenes,
                       String promptLanguage, String translationMode, String continuity,
                       double continuityStrength, String audio, String finalFile,
                       String qualityGate, int qualityPassedScenes, int qualityTotalScenes,
                       String error) {
        this.stage = clean(stage);
        this.aiOk = aiOk;
        this.engine = clean(engine);
        this.scenes = scenes;
        this.promptLanguage = clean(promptLanguage);
        this.translationMode = clean(translationMode);
        this.continuity = clean(continuity);
        this.continuityStrength = continuityStrength;
        this.audio = clean(audio);
        this.finalFile = clean(finalFile);
        this.qualityGate = clean(qualityGate);
        this.qualityPassedScenes = qualityPassedScenes;
        this.qualityTotalScenes = qualityTotalScenes;
        this.error = clean(error);
    }

    static LiveE2ECertificate parse(String jsonText) throws Exception {
        JSONObject j = new JSONObject(jsonText == null ? "{}" : jsonText);
        JSONObject translation = j.optJSONObject("translation");
        JSONArray quality = j.optJSONArray("quality");
        int passed = 0;
        if (quality != null) {
            for (int i = 0; i < quality.length(); i++) {
                JSONObject q = quality.optJSONObject(i);
                if (q != null && q.optBoolean("pass", false)) passed++;
            }
        }
        return new LiveE2ECertificate(
                j.optString("stage", ""),
                j.optBoolean("ai_ok", false),
                j.optString("engine", ""),
                j.optInt("scenes", 0),
                j.optString("prompt_language", ""),
                translation == null ? "" : translation.optString("mode", ""),
                j.optString("continuity", ""),
                j.optDouble("continuity_strength", -1.0),
                j.optString("audio", ""),
                j.optString("final", ""),
                j.optString("quality_gate", ""),
                passed,
                quality == null ? 0 : quality.length(),
                j.optString("error", "")
        );
    }

    boolean passesCanonicalV4() {
        return aiOk
                && "COMPLETE".equals(stage.toUpperCase(Locale.US))
                && engine.toLowerCase(Locale.US).contains("story-v4")
                && scenes == 5
                && "english".equals(promptLanguage.toLowerCase(Locale.US))
                && "tr_to_en".equals(translationMode.toLowerCase(Locale.US))
                && "previous_scene_last_frame".equals(continuity)
                && continuityStrength >= 0.55 && continuityStrength <= 0.75
                && audio.toLowerCase(Locale.US).contains("aac")
                && "FINAL.mp4".equals(finalFile)
                && "siglip_semantic_plus_visual_integrity".equals(qualityGate)
                && qualityTotalScenes == 5
                && qualityPassedScenes == 5
                && error.isEmpty();
    }

    String failureReason() {
        if (passesCanonicalV4()) return "";
        if (!aiOk) return "ai_ok=false" + suffixError();
        if (!"COMPLETE".equals(stage.toUpperCase(Locale.US))) return "stage=" + stage;
        if (!engine.toLowerCase(Locale.US).contains("story-v4")) return "engine=" + engine;
        if (scenes != 5) return "scenes=" + scenes;
        if (!"english".equals(promptLanguage.toLowerCase(Locale.US))) return "prompt_language=" + promptLanguage;
        if (!"tr_to_en".equals(translationMode.toLowerCase(Locale.US))) return "translation.mode=" + translationMode;
        if (!"previous_scene_last_frame".equals(continuity)) return "continuity=" + continuity;
        if (continuityStrength < 0.55 || continuityStrength > 0.75) return "continuity_strength=" + continuityStrength;
        if (!audio.toLowerCase(Locale.US).contains("aac")) return "audio=" + audio;
        if (!"FINAL.mp4".equals(finalFile)) return "final=" + finalFile;
        if (!"siglip_semantic_plus_visual_integrity".equals(qualityGate)) return "quality_gate=" + qualityGate;
        if (qualityPassedScenes != 5) return "quality_passed_scenes=" + qualityPassedScenes;
        if (qualityTotalScenes != 5) return "quality_total_scenes=" + qualityTotalScenes;
        if (!error.isEmpty()) return "error=" + error;
        return "unknown certificate mismatch";
    }

    String summary() {
        return "engine=" + engine
                + ", scenes=" + scenes
                + ", prompt=" + promptLanguage
                + ", translation=" + translationMode
                + ", continuity=" + continuity
                + "@" + continuityStrength
                + ", audio=" + audio
                + ", quality=" + qualityGate + "(" + qualityPassedScenes + "/" + qualityTotalScenes + ")"
                + ", final=" + finalFile;
    }

    private String suffixError() {
        return error.isEmpty() ? "" : ", error=" + error;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
