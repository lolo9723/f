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
        String currentSession = exact(currentSessionId);
        String expectedSession = exact(expectedSessionId);
        if (!isCanonicalSessionIdentity(expectedSession)
                || !isCanonicalSessionIdentity(currentSession)
                || !expectedSession.equals(currentSession)) {
            return false;
        }

        String current = exact(currentAnchor);
        String expected = exact(expectedAnchor);

        // Empty is the only authority-free design state that may resume. It forces the next
        // teacher cycle to prove and BIND_DESIGN from the live Canva editor before a durable
        // design identity exists again.
        if (current.isEmpty() || expected.isEmpty()) {
            return current.isEmpty() && expected.isEmpty();
        }

        // Resume must accept exactly the same design-identity language as durable persistence.
        // In particular, canonically equivalent but non-NFC Unicode titles must never become
        // resume authority merely because the same corrupted value appears on both sides.
        if (!DesignAnchorPersistencePolicy.isPersistableAnchor(current)
                || !DesignAnchorPersistencePolicy.isPersistableAnchor(expected)
                || !current.equals(current.trim())
                || !expected.equals(expected.trim())
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
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            if (isForbiddenIdentityCodePoint(codePoint)
                    || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isForbiddenIdentityCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE;
    }
}
