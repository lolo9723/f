package com.videofabrikasi.app;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class VideoFactoryScriptTest {
    private String script() {
        return VideoFactoryScript.build(
                "Küçük bir anahtar panikle kilitli bir kapıya koşuyor; kapının arkasındaki sır ancak finalde anlaşılıyor.",
                "p1");
    }

    @Test public void v2IsTheActiveProductionEngine() {
        String s = script();
        assertTrue(s.contains("story-v2"));
        assertTrue(s.contains("CONTINUITY_STRENGTH = 0.65"));
        assertTrue(s.contains("SCENE_ROLES = ["));
        assertTrue(s.contains("PROMPTS = build_scene_prompts(USER_IDEA)"));
    }

    @Test public void genericStoryUsesFiveDistinctDramaticRoles() {
        String s = script();
        assertTrue(s.contains("'HOOK'"));
        assertTrue(s.contains("'ESCALATION'"));
        assertTrue(s.contains("'TURNING_POINT'"));
        assertTrue(s.contains("'CONSEQUENCE'"));
        assertTrue(s.contains("'PAYOFF'"));
        assertTrue(s.contains("Scene {index}/5 role: {role}"));
        assertTrue(s.contains("Show one dominant action only"));
    }

    @Test public void activeEngineHasNoHardCodedLetterOrMailboxPremise() {
        String s = script().toLowerCase();
        assertFalse(s.contains("white envelope"));
        assertFalse(s.contains("street mailbox"));
        assertFalse(s.contains("happy envelope"));
        assertFalse(s.contains("worried envelope"));
        assertTrue(s.contains("use only characters, objects, setting and events supported by the creator story"));
    }

    @Test public void firstSceneIsTextToVideoAndLaterScenesUsePreviousLastFrame() {
        String s = script();
        assertTrue(s.contains("continuity_frame=None"));
        assertTrue(s.contains("'text_to_video' if i == 0 else 'previous_scene_last_frame'"));
        assertTrue(s.contains("if i > 0:"));
        assertTrue(s.contains("conditioning_media_paths=[str(continuity_frame)]"));
        assertTrue(s.contains("conditioning_strengths=[CONTINUITY_STRENGTH]"));
        assertTrue(s.contains("extract_last_frame(out, next_frame)"));
        assertTrue(s.contains("continuity_frame=next_frame"));
    }

    @Test public void ltxEngineIsPinnedAndT4Fp16Compatible() {
        String s = script();
        assertTrue(s.contains("LTX_COMMIT = '4b2d053057623ddd4d0a1d3e9cd28890e9ef487f'"));
        assertTrue(s.contains("cfg_data['precision'] = 'float16'"));
        assertTrue(s.contains("elif precision == \"float16\":"));
        assertTrue(s.contains("to(torch.float16)"));
        assertTrue(s.contains("compute_dtype = torch.float16 if precision == \"float16\" else torch.bfloat16"));
        assertTrue(s.contains("transformers==4.49.0"));
        assertTrue(s.contains("diffusers==0.33.1"));
        assertTrue(s.contains("torch.cuda.is_available()"));
        assertTrue(s.contains("device='cuda', dtype=torch.float16"));
        assertTrue(s.contains("GPU VRAM is too small"));
    }

    @Test public void scenesRetryAndPipelineCanRecoverAfterFailure() {
        String s = script();
        assertTrue(s.contains("MAX_SCENE_ATTEMPTS = 3"));
        assertTrue(s.contains("for attempt in range(1, MAX_SCENE_ATTEMPTS + 1):"));
        assertTrue(s.contains("reset_pipeline_cache()"));
        assertTrue(s.contains("failed after {MAX_SCENE_ATTEMPTS} attempts"));
        assertTrue(s.contains("validate_scene_media(out)"));
    }

    @Test public void kaggleWorkingKeepsOnlyPersistentOutputsByDesign() {
        String s = script();
        assertTrue(s.contains("WORK = Path('/kaggle/working')"));
        assertTrue(s.contains("TEMP = Path('/tmp/video-factory')"));
        assertTrue(s.contains("FINAL = WORK / 'FINAL.mp4'"));
        assertTrue(s.contains("STATUS = WORK / 'status.json'"));
        assertTrue(s.contains("SCENES = TEMP / 'scenes'"));
        assertTrue(s.contains("repo = TEMP/'LTX-Video'"));
        assertTrue(s.contains("custom_cfg = TEMP/'ltx_t4_config.yaml'"));
        assertTrue(s.contains("concat=TEMP/'concat.txt'"));
        assertFalse(s.contains("SCENES = WORK"));
    }

    @Test public void finalProductionIncludesHighEmotionAudioAndMediaValidation() {
        String s = script();
        assertTrue(s.contains("def build_soundtrack(path, duration):"));
        assertTrue(s.contains("High-arousal opening vocal-like scream"));
        assertTrue(s.contains("soundtrack.wav"));
        assertTrue(s.contains("'-c:a','aac'"));
        assertTrue(s.contains("audio='procedural_generic_emotion_sfx_aac'"));
        assertTrue(s.contains("def validate_final_media(path):"));
        assertTrue(s.contains("Final audio stream is missing"));
        assertTrue(s.contains("not 1080x1920"));
    }

    @Test public void generatedPythonPassesRealSyntaxCompilation() throws Exception {
        String s = VideoFactoryScript.build(
                "Türkçe fikir: korkmuş bir bavul kapıdan kaçıyor ve gizli nedeni finalde ortaya çıkıyor.",
                "syntax-test");
        Path dir = Files.createTempDirectory("video-factory-python-v2-test");
        Path py = dir.resolve("generated.py");
        Files.write(py, s.getBytes(StandardCharsets.UTF_8));

        Process p = new ProcessBuilder("python3", "-m", "py_compile", py.toString())
                .redirectErrorStream(true).start();
        String output = readAll(p.getInputStream());
        int code = p.waitFor();
        assertEquals("Generated Python syntax error: " + output, 0, code);
    }

    @Test public void realPythonPlannerExecutesFiveGenericPromptsWithoutGpu() throws Exception {
        String story = "Korkmuş bir bavul havaalanı kapısından kaçıyor; görünmeyen sırrı yalnız finalde ortaya çıkıyor.";
        String s = VideoFactoryScript.build(story, "planner-test");
        Path dir = Files.createTempDirectory("video-factory-planner-test");
        Path generated = dir.resolve("generated.py");
        Path runner = dir.resolve("planner_runner.py");
        Files.write(generated, s.getBytes(StandardCharsets.UTF_8));

        String helper = """
import ast, json, sys
source = open(sys.argv[1], encoding='utf-8').read()
tree = ast.parse(source)
selected = []
for node in tree.body:
    if isinstance(node, ast.Assign):
        names = [t.id for t in node.targets if isinstance(t, ast.Name)]
        if any(name in ('SCENE_ROLES', 'STYLE_RULES') for name in names):
            selected.append(node)
    elif isinstance(node, ast.FunctionDef) and node.name == 'build_scene_prompts':
        selected.append(node)
module = ast.Module(body=selected, type_ignores=[])
ns = {}
exec(compile(module, '<planner-only>', 'exec'), ns)
story = sys.argv[2]
prompts = ns['build_scene_prompts'](story)
print(json.dumps({'roles':[r[0] for r in ns['SCENE_ROLES']], 'count':len(prompts), 'prompts':prompts}, ensure_ascii=False))
""";
        Files.write(runner, helper.getBytes(StandardCharsets.UTF_8));

        Process p = new ProcessBuilder("python3", runner.toString(), generated.toString(), story)
                .redirectErrorStream(true).start();
        String output = readAll(p.getInputStream());
        int code = p.waitFor();
        assertEquals("Planner execution failed: " + output, 0, code);
        assertTrue(output.contains("\"count\": 5"));
        assertTrue(output.contains("HOOK"));
        assertTrue(output.contains("ESCALATION"));
        assertTrue(output.contains("TURNING_POINT"));
        assertTrue(output.contains("CONSEQUENCE"));
        assertTrue(output.contains("PAYOFF"));
        assertTrue(output.contains(story));
        String lower = output.toLowerCase();
        assertFalse(lower.contains("white envelope"));
        assertFalse(lower.contains("street mailbox"));
    }

    @Test public void userIdeaAndProjectIdAreBase64Embedded() {
        String s = VideoFactoryScript.build("a\"\"\"b", "id/özel");
        assertFalse(s.contains("USER_IDEA = a\"\"\"b"));
        assertTrue(s.contains("base64.b64decode"));
        assertTrue(s.contains("PROJECT_ID = base64.b64decode"));
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
