package com.videofabrikasi.app;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class VideoFactoryScriptV4Test {
    private String script() {
        return VideoFactoryScriptV4.build(
                "Korkmuş bir valiz havaalanı kapısından kaçıyor; gizli nedeni finalde ortaya çıkıyor.",
                "v4-test");
    }

    @Test public void v4AddsPinnedApacheFriendlySiglipQualityGate() {
        String s = script();
        assertTrue(s.contains("story-v4"));
        assertFalse(s.contains("story-v3"));
        assertTrue(s.contains("QC_MODEL = 'google/siglip-base-patch16-224'"));
        assertTrue(s.contains("QC_REVISION = '8d307961cfc45a1bbceecac290bec0a07e9a48db'"));
        assertTrue(s.contains("QC_MIN_PROMPT_COSINE = 0.04"));
        assertTrue(s.contains("QC_MIN_CONTINUITY_COSINE = 0.05"));
        assertTrue(s.contains("QC_MAX_NEAR_BLACK_RATIO = 0.65"));
        assertTrue(s.contains("QC_MIN_FRAME_CHANGE = 0.0015"));
        assertTrue(s.contains("def semantic_scene_qc("));
        assertTrue(s.contains("model.get_image_features"));
        assertTrue(s.contains("model.get_text_features"));
        assertTrue(s.contains(".to('cpu')"));
        assertTrue(s.contains("quality_gate='siglip_semantic_plus_visual_integrity'"));
        assertTrue(s.contains("quality_report.json"));
    }

    @Test public void qcFailureFeedsExistingBoundedSceneRetryLoop() {
        String s = script();
        assertTrue(s.contains("qc = semantic_scene_qc("));
        assertTrue(s.contains("if not qc['pass']:"));
        assertTrue(s.contains("raise RuntimeError('Semantic/visual QC failed:"));
        assertTrue(s.contains("for attempt in range(1, MAX_SCENE_ATTEMPTS + 1):"));
        assertTrue(s.contains("MAX_SCENE_ATTEMPTS = 3"));
        assertTrue(s.contains("reset_pipeline_cache()"));
        assertTrue(s.contains("scene_qc_report.append(qc)"));
    }

    @Test public void successfulScenesArePersistedOnlyForCrossRunRepair() {
        String s = script();
        assertTrue(s.contains("shutil.copy2(out, WORK/f'scene_{i+1}.mp4')"));
        assertTrue(s.contains("SCENES = TEMP / 'scenes'"));
        assertTrue(s.contains("FINAL = WORK / 'FINAL.mp4'"));
        assertTrue(s.contains("STATUS = WORK / 'status.json'"));
    }

    @Test public void promptReadyStageDoesNotReferenceFutureQcReport() {
        String s = script();
        int start = s.indexOf("stage='PROMPTS_READY'");
        int end = s.indexOf("gpu_name, gpu_vram_gb, torch_version = gpu_preflight()", start);
        assertTrue(start >= 0);
        assertTrue(end > start);
        assertFalse(s.substring(start, end).contains("scene_qc_report"));
    }

    @Test public void qualityDecisionActuallyExecutesWithoutModelDownload() throws Exception {
        String s = script();
        Path dir = Files.createTempDirectory("video-factory-v4-qc-test");
        Path generated = dir.resolve("generated.py");
        Path runner = dir.resolve("qc_runner.py");
        Files.write(generated, s.getBytes(StandardCharsets.UTF_8));

        String helper = """
import ast, json, sys
source=open(sys.argv[1], encoding='utf-8').read()
tree=ast.parse(source)
selected=[]
for node in tree.body:
    if isinstance(node, ast.Assign):
        names=[t.id for t in node.targets if isinstance(t, ast.Name)]
        if any(n in (
            'QC_MIN_PROMPT_COSINE','QC_MIN_CONTINUITY_COSINE',
            'QC_MAX_NEAR_BLACK_RATIO','QC_MIN_FRAME_CHANGE'
        ) for n in names):
            selected.append(node)
    elif isinstance(node, ast.FunctionDef) and node.name == 'quality_gate_decision':
        selected.append(node)
ns={}
exec(compile(ast.Module(body=selected, type_ignores=[]), '<qc-only>', 'exec'), ns)
fn=ns['quality_gate_decision']
good=fn(0.20,0.30,0.02,0.08)
bad_prompt=fn(-0.10,0.30,0.02,0.08)
bad_cont=fn(0.20,-0.20,0.02,0.08)
bad_black=fn(0.20,0.30,0.90,0.08)
bad_freeze=fn(0.20,0.30,0.02,0.0001)
print(json.dumps({
  'good':good,'bad_prompt':bad_prompt,'bad_cont':bad_cont,
  'bad_black':bad_black,'bad_freeze':bad_freeze
}))
""";
        Files.write(runner, helper.getBytes(StandardCharsets.UTF_8));
        Process p = new ProcessBuilder("python3", runner.toString(), generated.toString())
                .redirectErrorStream(true).start();
        String output = readAll(p.getInputStream());
        int code = p.waitFor();
        assertEquals("QC helper execution failed: " + output, 0, code);
        assertTrue(output.contains("\"good\": {\"pass\": true"));
        assertTrue(output.contains("\"bad_prompt\": {\"pass\": false"));
        assertTrue(output.contains("\"bad_cont\": {\"pass\": false"));
        assertTrue(output.contains("\"bad_black\": {\"pass\": false"));
        assertTrue(output.contains("\"bad_freeze\": {\"pass\": false"));
    }

    @Test public void generatedV4PythonPassesRealSyntaxCompilation() throws Exception {
        String s = script();
        Path dir = Files.createTempDirectory("video-factory-python-v4-test");
        Path py = dir.resolve("generated.py");
        Files.write(py, s.getBytes(StandardCharsets.UTF_8));
        Process p = new ProcessBuilder("python3", "-m", "py_compile", py.toString())
                .redirectErrorStream(true).start();
        String output = readAll(p.getInputStream());
        int code = p.waitFor();
        assertEquals("Generated V4 Python syntax error: " + output, 0, code);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
