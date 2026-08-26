package com.videofabrikasi.app;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test public void t4PathForcesFloat16InsteadOfNativeBfloat16() {
        String s = VideoFactoryScript.build("test", "p1");
        assertTrue(s.contains("cfg_data['precision'] = 'float16'"));
        assertTrue(s.contains("elif precision == \"float16\":"));
        assertTrue(s.contains("to(torch.float16)"));
        assertTrue(s.contains("compute_dtype = torch.float16 if precision == \"float16\" else torch.bfloat16"));
        assertTrue(s.contains("Pinned LTX transformer precision block changed unexpectedly"));
        assertTrue(s.contains("Pinned LTX VAE/text encoder precision block changed unexpectedly"));
        assertTrue(s.contains("T4-FP16"));
    }

    @Test public void kaggleRuntimeHasGpuPreflightAndCompatibleDependencyPins() {
        String s = VideoFactoryScript.build("test", "p1");
        assertTrue(s.contains("transformers==4.49.0"));
        assertTrue(s.contains("diffusers==0.33.1"));
        assertTrue(s.contains("torch.cuda.is_available()"));
        assertTrue(s.contains("device='cuda', dtype=torch.float16"));
        assertTrue(s.contains("GPU VRAM is too small"));
        assertTrue(s.contains("stage='GPU_READY'"));
    }

    @Test public void generatedPythonPassesRealSyntaxCompilation() throws Exception {
        String s = VideoFactoryScript.build("Türkçe fikir: çığlık atan mektup", "syntax-test");
        Path dir = Files.createTempDirectory("video-factory-python-test");
        Path py = dir.resolve("generated.py");
        Files.write(py, s.getBytes(StandardCharsets.UTF_8));

        Process p = new ProcessBuilder("python3", "-m", "py_compile", py.toString())
                .redirectErrorStream(true).start();
        InputStream in = p.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        int code = p.waitFor();
        String output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("Generated Python syntax error: " + output, 0, code);
    }

    @Test public void userIdeaIsBase64Embedded() {
        String s = VideoFactoryScript.build("a\"\"\"b", "id");
        assertFalse(s.contains("USER_IDEA = a\"\"\"b"));
        assertTrue(s.contains("base64.b64decode"));
    }
}
