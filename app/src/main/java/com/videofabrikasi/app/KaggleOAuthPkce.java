package com.videofabrikasi.app;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pure-Java implementation of the public-client PKCE contract used by Kaggle's
 * official open-source SDK. The registered public client uses localhost
 * redirects on ports 8000-9000.
 */
final class KaggleOAuthPkce {
    static final String CLIENT_ID = "kagglesdk";
    static final String SCOPE = "resources.admin:*";
    static final int MIN_LOOPBACK_PORT = 8000;
    static final int MAX_LOOPBACK_PORT = 9000;

    private KaggleOAuthPkce() {}

    static String newState() {
        return UUID.randomUUID().toString();
    }

    static String newCodeVerifier() {
        byte[] random = new byte[64];
        new SecureRandom().nextBytes(random);
        // Python secrets.token_urlsafe(64), used by Kaggle's SDK, is URL-safe
        // base64 without padding.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    static String codeChallenge(String verifier) throws Exception {
        if (verifier == null || verifier.length() < 43 || verifier.length() > 128) {
            throw new IllegalArgumentException("PKCE verifier length must be 43..128.");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.UTF_8));
        // Match kagglesdk.KaggleOAuth exactly: urlsafe_b64encode retains padding.
        return Base64.getUrlEncoder().encodeToString(digest);
    }

    static String authorizationUrl(int port, String state, String challenge) throws Exception {
        if (port < MIN_LOOPBACK_PORT || port > MAX_LOOPBACK_PORT) {
            throw new IllegalArgumentException("Kaggle OAuth loopback port out of range.");
        }
        if (state == null || state.isEmpty() || challenge == null || challenge.isEmpty()) {
            throw new IllegalArgumentException("Kaggle OAuth state/challenge empty.");
        }
        String redirect = "http://localhost:" + port;
        return "https://www.kaggle.com/api/v1/oauth2/authorize"
                + "?response_type=code"
                + "&client_id=" + enc(CLIENT_ID)
                + "&redirect_uri=" + enc(redirect)
                + "&scope=" + enc(SCOPE)
                + "&state=" + enc(state)
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256"
                + "&response_mode=query";
    }

    static Callback parseCallbackTarget(String target) throws Exception {
        String clean = target == null ? "" : target.trim();
        if (clean.isEmpty() || !clean.startsWith("/")) {
            throw new IllegalArgumentException("Invalid OAuth callback target.");
        }
        URI uri = URI.create("http://localhost" + clean);
        Map<String,String> query = parseQuery(uri.getRawQuery());
        return new Callback(
                query.getOrDefault("code", ""),
                query.getOrDefault("state", ""),
                query.getOrDefault("error", ""),
                query.getOrDefault("error_description", "")
        );
    }

    private static Map<String,String> parseQuery(String raw) throws Exception {
        Map<String,String> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            result.put(dec(key), dec(value));
        }
        return result;
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String dec(String value) throws Exception {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
    }

    static final class Callback {
        final String code;
        final String state;
        final String error;
        final String errorDescription;

        Callback(String code, String state, String error, String errorDescription) {
            this.code = code == null ? "" : code;
            this.state = state == null ? "" : state;
            this.error = error == null ? "" : error;
            this.errorDescription = errorDescription == null ? "" : errorDescription;
        }

        boolean successfulFor(String expectedState) {
            return error.isEmpty()
                    && !code.isEmpty()
                    && expectedState != null
                    && expectedState.equals(state);
        }
    }
}
