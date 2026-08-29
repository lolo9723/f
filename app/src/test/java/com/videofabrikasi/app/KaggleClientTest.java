package com.videofabrikasi.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class KaggleClientTest {
    @Test public void slugifyIsStableAndSafe() {
        assertEquals("iki-mektup-cok-kotu-haber", KaggleClient.slugify("İki mektup: Çok kötü haber!"));
        assertFalse(KaggleClient.slugify("////").isEmpty());
        assertTrue(KaggleClient.slugify("abcdefghijklmnopqrstuvwxyz0123456789-abcdef").length() <= 38);
    }

    @Test public void statusNormalizationWorks() {
        assertEquals("TAMAMLANDI", KaggleClient.normalizeStatus("COMPLETE"));
        assertEquals("ÜRETİLİYOR", KaggleClient.normalizeStatus("RUNNING"));
        assertEquals("KUYRUKTA", KaggleClient.normalizeStatus("QUEUED"));
        assertEquals("KUYRUKTA", KaggleClient.normalizeStatus("NEW_SCRIPT"));
        assertEquals("HATALI", KaggleClient.normalizeStatus("ERROR"));
    }


    @Test public void diagnosticLogSummaryKeepsUsefulFailureLines() {
        String raw = "boot ok\n"
                + "loading model\n"
                + "Traceback (most recent call last):\n"
                + "RuntimeError: CUDA out of memory\n"
                + "some trailing noise\n";
        String summary = KaggleClient.diagnosticLogSummary(raw);
        assertTrue(summary.contains("Traceback"));
        assertTrue(summary.contains("RuntimeError: CUDA out of memory"));
        assertFalse(summary.contains("boot ok"));
    }

    @Test public void diagnosticLogSummaryPreservesPythonStackFrames() {
        String raw = "preface noise\n"
                + "Traceback (most recent call last):\n"
                + "  File \"/kaggle/working/script.py\", line 211, in <module>\n"
                + "    tokenizer = MarianTokenizer(...)\n"
                + "  File \"/usr/local/lib/python3.12/site-packages/x.py\", line 7, in load\n"
                + "    open(path)\n"
                + "TypeError: expected str, bytes or os.PathLike object, not NoneType\n";
        String summary = KaggleClient.diagnosticLogSummary(raw);
        assertTrue(summary.contains("script.py"));
        assertTrue(summary.contains("line 211"));
        assertTrue(summary.contains("TypeError"));
        assertFalse(summary.contains("preface noise"));
    }

    @Test public void oauthTokenResponseParsesSnakeAndCamelCase() throws Exception {
        KaggleClient.OAuthToken snake = KaggleClient.oauthTokenFromJson(
                "{\"access_token\":\"a\",\"refresh_token\":\"r\","
                        + "\"username\":\"user\",\"expires_in\":3600,\"scope\":\"resources.admin:*\"}");
        assertEquals("a", snake.accessToken);
        assertEquals("r", snake.refreshToken);
        assertEquals("user", snake.username);
        assertEquals(3600L, snake.expiresInSeconds);
        assertTrue(snake.usable());

        KaggleClient.OAuthToken camel = KaggleClient.oauthTokenFromJson(
                "{\"accessToken\":\"a2\",\"refreshToken\":\"r2\","
                        + "\"userName\":\"user2\",\"expiresIn\":7200}");
        assertEquals("a2", camel.accessToken);
        assertEquals("r2", camel.refreshToken);
        assertEquals("user2", camel.username);
        assertEquals(7200L, camel.expiresInSeconds);
    }

    @Test public void officialKaggleRpcHostIsUsed() {
        assertEquals("https://api.kaggle.com/v1", KaggleClient.RPC);
        assertEquals("https://api.kaggle.com/api/v1", KaggleClient.REST);
    }

    @Test public void signedDownloadUrlMustBeHttpsAndCredentialFree() {
        assertEquals("https://storage.example/file", KaggleClient.requireHttpsUrl("https://storage.example/file"));
        try {
            KaggleClient.requireHttpsUrl("http://storage.example/file");
            fail("HTTP URL should be rejected");
        } catch (IllegalArgumentException expected) {}
        try {
            KaggleClient.requireHttpsUrl("https://user:pass@storage.example/file");
            fail("Credential-bearing URL should be rejected");
        } catch (IllegalArgumentException expected) {}
    }
}
