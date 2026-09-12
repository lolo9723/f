package com.emrah.canvaapprentice;

public final class TeacherSessionPolicy {
    private TeacherSessionPolicy() {}

    public static boolean isCurrent(String expectedSessionId, String currentSessionId, TaskState.Mode mode) {
        return isCanonicalSessionIdentity(expectedSessionId)
                && isCanonicalSessionIdentity(currentSessionId)
                && expectedSessionId.equals(currentSessionId)
                && mode == TaskState.Mode.RUNNING;
    }

    /**
     * Teacher session ids are authority-bearing runtime identity. They must never be
     * silently treated as equivalent after trimming or control-character cleanup: a stale
     * callback holding a contaminated id must fail closed instead of matching another layer's
     * normalized value. Session ids are generated locally, so whitespace/control bytes have
     * no legitimate meaning here.
     */
    private static boolean isCanonicalSessionIdentity(String value) {
        if (value == null || value.isEmpty() || value.length() > 128 || !value.equals(value.trim())) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) return false;
        }
        return true;
    }
}
