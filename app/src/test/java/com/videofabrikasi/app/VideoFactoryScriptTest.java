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

    @Test public void v4IsTheActiveProductionEngine() {
        String s = script();
        assertTrue(s.contains("story-v4"));
        assertFalse(s.contains("story-v3"));
        assertFalse(s.contains("story-v2"));
        assertTrue(s.contains("QC_MODEL = 'google/siglip-base-patch16-224'"));
        assertTrue(s.contains("quality_gate='siglip_semantic_plus_visual_integrity'"));
        assertTrue(s.contains("CONTINUITY_STRENGTH = 0.65"));
        assertTrue(s.contains("SCENE_ROLES = ["));
        assertTrue(s.contains("PROMPTS = build_scene_prompts(LTX_STORY)"));
        assertFalse(s.contains("PROMPTS = build_scene_prompts(USER_IDEA)"));
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

    @Test public void turkishStoryIsPreparedInEnglishOnCpuBeforeLtxPrompts() {
        String s = script();
        assertTrue(s.contains("TRANSLATION_MODEL = 'Helsinki-NLP/opus-mt-tr-en'"));
        assertTrue(s.contains("TRANSLATION_REVISION = '19c65427cc2af5f191337d4899e0348c4af25902'"));
        assertTrue(s.contains("MarianTokenizer("));
        assertTrue(s.contains("MarianMTModel.from_pretrained"));
        assertTrue(s.contains("filename='source.spm'"));
        assertTrue(s.contains("filename='target.spm'"));
        assertTrue(s.contains("filename='vocab.json'"));
        assertFalse(s.contains("AutoTokenizer.from_pretrained"));
        assertFalse(s.contains("AutoModelForSeq2SeqLM.from_pretrained"));
        assertTrue(s.contains("revision=TRANSLATION_REVISION"));
        assertTrue(s.contains(".to('cpu')"));
        assertTrue(s.contains("torch.inference_mode()"));
        assertTrue(s.contains("num_beams=4"));
        assertTrue(s.contains("do_sample=False"));
        assertTrue(s.contains("sentencepiece==0.2.0"));
        assertTrue(s.contains("protobuf==5.29.5"));
        assertTrue(s.contains("sacremoses==0.1.1"));
        assertTrue(s.contains("huggingface-hub==0.30.2"));
        assertTrue(s.contains("safetensors==0.5.3"));
        assertTrue(s.contains("tokenizers==0.21.4"));
        assertTrue(s.contains("LTX_STORY, TRANSLATION_INFO = prepare_story_for_ltx(USER_IDEA)"));
        assertTrue(s.contains("prompt_language='English'"));
        assertTrue(s.contains("translation_still_turkish(result)"));
        assertTrue(s.contains("Translation still appears Turkish; refusing low-quality LTX prompt"));
        assertTrue(s.contains("'mode':'tr_to_en'"));
    }

    @Test public void turkishDetectionAndChunkingActuallyExecuteWithoutDownloadingModel() throws Exception {
        String s = VideoFactoryScript.build(
                "Bir anahtar kapıya doğru koşuyor ama neden kaçtığı finalde ortaya çıkıyor.",
                "language-test");
        Path dir = Files.createTempDirectory("video-factory-language-test");
        Path generated = dir.resolve("generated.py");
        Path runner = dir.resolve("language_runner.py");
        Files.write(generated, s.getBytes(StandardCharsets.UTF_8));

        String helper = """
import ast, json, sys
source = open(sys.argv[1], encoding='utf-8').read()
tree = ast.parse(source)
selected=[]
for node in tree.body:
    if isinstance(node, ast.Assign):
        names=[t.id for t in node.targets if isinstance(t, ast.Name)]
        if any(n in ('TURKISH_CHARS','TURKISH_HINT_WORDS') for n in names):
            selected.append(node)
    elif isinstance(node, ast.FunctionDef) and node.name in (
        'turkish_language_signals','looks_turkish','translation_still_turkish','split_translation_chunks'
    ):
        selected.append(node)
ns={}
exec(compile(ast.Module(body=selected, type_ignores=[]), '<language-only>', 'exec'), ns)
result={
  'turkish_ascii': ns['looks_turkish']('bir mektup kutuya kosuyor ama neden finalde ortaya cikiyor'),
  'turkish_unicode': ns['looks_turkish']('Küçük anahtar üzgün şekilde kapıya koşuyor'),
  'english': ns['looks_turkish']('A small key runs toward a locked door in panic'),
  'english_with_turkish_name': ns['looks_turkish']('Çağrı walks toward the old station in panic'),
  'output_name_ok': ns['translation_still_turkish']('Çağrı walks toward the old station in panic'),
  'output_real_turkish_rejected': ns['translation_still_turkish']('Bir anahtar üzgün şekilde kapıya doğru koşuyor'),
  'chunks': ns['split_translation_chunks']('Birinci cümle. İkinci cümle! Üçüncü cümle?', 20)
}
print(json.dumps(result, ensure_ascii=False))
""";
        Files.write(runner, helper.getBytes(StandardCharsets.UTF_8));
        Process p = new ProcessBuilder("python3", runner.toString(), generated.toString())
                .redirectErrorStream(true).start();
        String output = readAll(p.getInputStream());
        int code = p.waitFor();
        assertEquals("Language helper execution failed: " + output, 0, code);
        assertTrue(output.contains("\"turkish_ascii\": true"));
        assertTrue(output.contains("\"turkish_unicode\": true"));
        assertTrue(output.contains("\"english\": false"));
        assertTrue(output.contains("\"english_with_turkish_name\": false"));
        assertTrue(output.contains("\"output_name_ok\": false"));
        assertTrue(output.contains("\"output_real_turkish_rejected\": true"));
        assertTrue(output.contains("\"chunks\":"));
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
        assertTrue(s.contains("class InternetUnavailableError(RuntimeError):"));
        assertTrue(s.contains("def external_internet_preflight():"));
        assertTrue(s.contains("('github.com', 'huggingface.co', 'pypi.org')"));
        assertTrue(s.contains("stage='INTERNET_REQUIRED'"));
        assertTrue(s.contains("VIDEO_FACTORY_INTERNET_REQUIRED"));
        assertTrue(s.contains("https://codeload.github.com/Lightricks/LTX-Video/tar.gz/{LTX_COMMIT}"));
        assertTrue(s.contains("urllib.request.urlopen(req, timeout=120)"));
        assertTrue(s.contains("archive.stat().st_size < 4096"));
        assertFalse(s.contains("archive.stat().st_size < 100000"));
        assertTrue(s.contains("(p/'ltx_video'/'inference.py').is_file()"));
        assertTrue(s.contains("(p/'configs'/'ltxv-2b-0.9.6-distilled.yaml').is_file()"));
        assertTrue(s.contains("repo = materialize_ltx_source(TEMP/'LTX-Video')"));
        assertTrue(s.contains("http.version=HTTP/1.1"));
        assertFalse(s.contains("['git','clone','--filter=blob:none'"));
        assertTrue(s.contains("cfg_data['precision'] = 'float16'"));
        assertTrue(s.contains("elif precision == \"float16\":"));
        assertTrue(s.contains("to(torch.float16)"));
        assertTrue(s.contains("compute_dtype = torch.float16 if precision == \"float16\" else torch.bfloat16"));
        assertTrue(s.contains("transformers==4.49.0"));
        assertTrue(s.contains("diffusers==0.33.1"));
        assertTrue(s.contains("accelerate==1.6.0"));
        assertTrue(s.contains("low_vram_t4 = device == \"cuda\" and get_total_gpu_memory() < 24"));
        assertTrue(s.contains("pipeline.enable_model_cpu_offload(gpu_id=0, device=device)"));
        assertTrue(s.contains("Pinned LTX text-encoder offload block changed unexpectedly"));
        assertTrue(s.contains("torch.cuda.empty_cache()"));
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
        assertTrue(s.contains("repo = materialize_ltx_source(TEMP/'LTX-Video')"));
        assertTrue(s.contains("custom_cfg = TEMP/'ltx_t4_config.yaml'"));
        assertTrue(s.contains("concat=TEMP/'concat.txt'"));
        assertFalse(s.contains("SCENES = WORK"));
    }

    @Test public void certificationFailureCannotManufactureFallbackFinal() {
        String s = script();
        assertTrue(s.contains("stage='AI_FAILED'"));
        assertFalse(s.contains("stage='AI_FAILED_FALLBACK'"));
        assertFalse(s.contains("stage='COMPLETE_FALLBACK'"));
        assertFalse(s.contains("\n    fallback_video()\n"));
        assertTrue(s.contains("Never allow a prior/degraded artifact"));
        assertTrue(s.contains("WORK/'ai_error.txt'"));
        assertTrue(s.contains("WORK/'quality_report.json'"));
        assertTrue(s.contains("WORK.glob('scene_*.mp4')"));
        assertTrue(s.contains("VIDEO_FACTORY_AI_FAILED"));
    }

    @Test public void finalProductionIncludesHighEmotionAudioAndMediaValidation() {
        String s = script();
        assertTrue(s.contains("def build_soundtrack(path, duration):"));
        assertTrue(s.contains("def fallback_video():"));
        assertTrue(s.contains("import numpy as np"));
        assertTrue(s.contains("High-arousal opening vocal-like scream"));
        assertTrue(s.contains("soundtrack.wav"));
        assertTrue(s.contains("'-c:a','aac'"));
        assertTrue(s.contains("audio='procedural_generic_emotion_sfx_aac'"));
        assertTrue(s.contains("def validate_final_media(path):"));
        assertTrue(s.contains("stream=codec_name,codec_type,width,height:format=duration"));
        assertTrue(s.contains("Final video codec is not H.264"));
        assertTrue(s.contains("Final audio stream is missing"));
        assertTrue(s.contains("Final audio codec is not AAC"));
        assertTrue(s.contains("not 1080x1920"));
    }

    @Test public void generatedPythonPassesRealSyntaxCompilation() throws Exception {
        String s = VideoFactoryScript.build(
                "Türkçe fikir: korkmuş bir bavul kapıdan kaçıyor ve gizli nedeni finalde ortaya çıkıyor.",
                "syntax-test");
        Path dir = Files.createTempDirectory("video-factory-python-v4-test");
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
