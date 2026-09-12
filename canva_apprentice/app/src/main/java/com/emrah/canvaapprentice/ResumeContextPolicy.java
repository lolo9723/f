package com.emrah.canvaapprentice;

/** Fail-closed ownership check for DEVAM ET / service-restore resume chains. */
final class ResumeContextPolicy {
    private ResumeContextPolicy() {}

    static boolean isCurrent(TaskState.Mode mode, String currentAnchor, String currentSessionId,
                             String expectedAnchor, String expectedSessionId) {
        if (mode != TaskState.Mode.RUNNING) return false;

        // Resume ownership is identity-bearing authority. Never trim or otherwise normalize
        // session ids or design anchors here: "session-2" and " session-2 " (or the same
        // distinction for an anchor) must not be allowed to collapse into one authority.
        // A corrupted/padded persisted value therefore fails closed instead of silently
        // re-attaching a DEVAM ET chain to the wrong execution context.
        String currentSession = exact(currentSessionId);
        String expectedSession = exact(expectedSessionId);
        if (!isCanonicalSessionIdentity(expectedSession)
                || !isCanonicalSessionIdentity(currentSession)
                || !expectedSession.equals(currentSession)) {
            return false;
        }

        String current = exact(currentAnchor);
        String expected = exact(expectedAnchor);

        // An empty design anchor is a legitimate pre-bind task state: a newly started task may
        // be RUNNING before a unique existing Canva design has been proven and bound. Service
        // restoration / DEVAM ET must be able to resume that same unbound state, otherwise a
        // process restart can strand a valid task forever. Empty is allowed only symmetrically;
        // one empty and one bound anchor is still a continuity mismatch and fails closed.
        if (current.isEmpty() || expected.isEmpty()) {
            return current.isEmpty() && expected.isEmpty();
        }

        if (!isCanonicalAnchorIdentity(expected)
                || !isCanonicalAnchorIdentity(current)
                || !expected.equals(current)) {
            return false;
        }
        return true;
    }

    private static String exact(String value) {
        return value == null ? "" : value;
    }

    private static boolean isCanonicalSessionIdentity(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCanonicalAnchorIdentity(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) return false;
        }
        return true;
    }
}
