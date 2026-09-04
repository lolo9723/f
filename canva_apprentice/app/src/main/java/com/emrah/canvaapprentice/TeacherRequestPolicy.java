package com.emrah.canvaapprentice;

public final class TeacherRequestPolicy {
    private TeacherRequestPolicy() {}

    public static boolean isCurrent(String expectedSessionId,
                                    String currentSessionId,
                                    TaskState.Mode mode,
                                    String expectedRequestToken,
                                    String activeRequestToken) {
        if (!TeacherSessionPolicy.isCurrent(expectedSessionId, currentSessionId, mode)) return false;
        if (expectedRequestToken == null || expectedRequestToken.isEmpty()) return false;
        return expectedRequestToken.equals(activeRequestToken);
    }
}
