package com.emrah.canvaapprentice;

public final class TeacherRequestPolicy {
    private TeacherRequestPolicy() {}

    private static boolean isCanonicalRequestToken(String token) {
        if (token == null || token.isEmpty()) return false;
        for (int i = 0; i < token.length();) {
            int cp = token.codePointAt(i);
            int type = Character.getType(cp);
            if (Character.isWhitespace(cp)
                    || Character.isSpaceChar(cp)
                    || Character.isISOControl(cp)
                    || type == Character.FORMAT) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    public static boolean isCurrent(String expectedSessionId,
                                    String currentSessionId,
                                    TaskState.Mode mode,
                                    String expectedRequestToken,
                                    String activeRequestToken) {
        if (!TeacherSessionPolicy.isCurrent(expectedSessionId, currentSessionId, mode)) return false;
        if (!isCanonicalRequestToken(expectedRequestToken)
                || !isCanonicalRequestToken(activeRequestToken)) return false;
        return expectedRequestToken.equals(activeRequestToken);
    }

    public static boolean isCurrent(String expectedSessionId,
                                    String currentSessionId,
                                    TaskState.Mode mode,
                                    String expectedRequestToken,
                                    String activeRequestToken,
                                    String expectedDesignAnchor,
                                    String currentDesignAnchor) {
        if (!isCurrent(expectedSessionId, currentSessionId, mode,
                expectedRequestToken, activeRequestToken)) return false;
        if (expectedDesignAnchor == null || currentDesignAnchor == null) return false;
        return expectedDesignAnchor.equals(currentDesignAnchor);
    }
}
