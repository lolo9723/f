package com.videofabrikasi.app;

/** Pure-Java policy for deciding when an OAuth access token must be renewed. */
final class KaggleSessionPolicy {
    static final long REFRESH_EARLY_MS = 30L * 60L * 1000L;
    static final long SAFE_FALLBACK_MS = 2L * 60L * 1000L;

    private KaggleSessionPolicy() {}

    static boolean hasCredential(String accessToken, String refreshToken) {
        return !clean(accessToken).isEmpty() || !clean(refreshToken).isEmpty();
    }

    static boolean shouldRefresh(String accessToken, String refreshToken,
                                 long expiresAtMs, long nowMs) {
        if (clean(refreshToken).isEmpty()) return false;
        if (clean(accessToken).isEmpty()) return true;
        return expiresAtMs <= nowMs + REFRESH_EARLY_MS;
    }

    static boolean canUseAccessAfterRefreshFailure(String accessToken,
                                                   long expiresAtMs,
                                                   long nowMs) {
        return !clean(accessToken).isEmpty()
                && expiresAtMs > nowMs + SAFE_FALLBACK_MS;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
