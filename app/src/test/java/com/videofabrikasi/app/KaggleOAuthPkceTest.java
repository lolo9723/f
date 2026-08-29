package com.videofabrikasi.app;

import org.junit.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class KaggleOAuthPkceTest {
    @Test public void officialKaggleSdkPublicClientContractIsPinned() {
        assertEquals("kagglesdk", KaggleOAuthPkce.CLIENT_ID);
        assertEquals("resources.admin:*", KaggleOAuthPkce.SCOPE);
        assertEquals(8000, KaggleOAuthPkce.MIN_LOOPBACK_PORT);
        assertEquals(9000, KaggleOAuthPkce.MAX_LOOPBACK_PORT);
    }

    @Test public void pkceVerifierAndChallengeHaveExpectedShape() throws Exception {
        String verifier = KaggleOAuthPkce.newCodeVerifier();
        assertTrue(verifier.length() >= 43);
        assertTrue(verifier.length() <= 128);
        assertFalse(verifier.contains("="));
        String challenge = KaggleOAuthPkce.codeChallenge(verifier);
        assertTrue(challenge.length() >= 43);
        assertTrue(challenge.matches("[A-Za-z0-9_\\-=]+"));
    }

    @Test public void authorizationUrlMatchesKaggleSdkLoopbackShape() throws Exception {
        String url = KaggleOAuthPkce.authorizationUrl(8123, "state-123", "challenge==");
        assertTrue(url.startsWith("https://www.kaggle.com/api/v1/oauth2/authorize?"));
        Map<String,String> q = query(url);
        assertEquals("code", q.get("response_type"));
        assertEquals("kagglesdk", q.get("client_id"));
        assertEquals("http://localhost:8123", q.get("redirect_uri"));
        assertEquals("resources.admin:*", q.get("scope"));
        assertEquals("state-123", q.get("state"));
        assertEquals("challenge==", q.get("code_challenge"));
        assertEquals("S256", q.get("code_challenge_method"));
        assertEquals("query", q.get("response_mode"));
    }

    @Test public void callbackRequiresMatchingStateAndCode() throws Exception {
        KaggleOAuthPkce.Callback good = KaggleOAuthPkce.parseCallbackTarget(
                "/?code=abc%2B123&state=expected");
        assertEquals("abc+123", good.code);
        assertTrue(good.successfulFor("expected"));
        assertFalse(good.successfulFor("wrong"));

        KaggleOAuthPkce.Callback denied = KaggleOAuthPkce.parseCallbackTarget(
                "/?error=access_denied&error_description=Nope&state=expected");
        assertFalse(denied.successfulFor("expected"));
        assertEquals("access_denied", denied.error);
        assertEquals("Nope", denied.errorDescription);
    }

    private static Map<String,String> query(String url) throws Exception {
        int qm = url.indexOf('?');
        Map<String,String> out = new HashMap<>();
        for (String pair : url.substring(qm + 1).split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(
                    URLDecoder.decode(k, StandardCharsets.UTF_8.name()),
                    URLDecoder.decode(v, StandardCharsets.UTF_8.name())
            );
        }
        return out;
    }
}
