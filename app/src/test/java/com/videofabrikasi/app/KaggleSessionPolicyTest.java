package com.videofabrikasi.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class KaggleSessionPolicyTest {
    @Test public void credentialExistsWithEitherAccessOrRefreshToken() {
        assertFalse(KaggleSessionPolicy.hasCredential("", ""));
        assertTrue(KaggleSessionPolicy.hasCredential("access", ""));
        assertTrue(KaggleSessionPolicy.hasCredential("", "refresh"));
        assertTrue(KaggleSessionPolicy.hasCredential(" access ", " refresh "));
    }

    @Test public void oauthRefreshesEarlyAndWhenAccessMissing() {
        long now = 1_000_000L;
        assertFalse(KaggleSessionPolicy.shouldRefresh(
                "access", "", now, now));
        assertTrue(KaggleSessionPolicy.shouldRefresh(
                "", "refresh", now + 60L * 60L * 1000L, now));
        assertTrue(KaggleSessionPolicy.shouldRefresh(
                "access", "refresh", now + 20L * 60L * 1000L, now));
        assertFalse(KaggleSessionPolicy.shouldRefresh(
                "access", "refresh", now + 45L * 60L * 1000L, now));
    }

    @Test public void expiredOrNearlyExpiredAccessCannotMaskRefreshFailure() {
        long now = 5_000_000L;
        assertFalse(KaggleSessionPolicy.canUseAccessAfterRefreshFailure(
                "", now + 10_000L, now));
        assertFalse(KaggleSessionPolicy.canUseAccessAfterRefreshFailure(
                "access", now + 60_000L, now));
        assertTrue(KaggleSessionPolicy.canUseAccessAfterRefreshFailure(
                "access", now + 5L * 60L * 1000L, now));
    }
}
