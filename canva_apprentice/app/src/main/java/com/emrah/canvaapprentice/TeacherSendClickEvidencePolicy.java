package com.emrah.canvaapprentice;

/** Pure fail-closed policy for revalidating a captured teacher Send click target. */
final class TeacherSendClickEvidencePolicy {
    private TeacherSendClickEvidencePolicy() {}

    static boolean mayDispatch(boolean transportCurrent,
                               boolean nodePresent,
                               boolean visibleToUser,
                               boolean enabled,
                               boolean clickable,
                               String capturedIdentity,
                               String currentIdentity) {
        if (!transportCurrent || !nodePresent || !visibleToUser || !enabled || !clickable) return false;
        if (!isSafeIdentity(capturedIdentity) || !isSafeIdentity(currentIdentity)) return false;
        return capturedIdentity.equals(currentIdentity);
    }

    private static boolean isSafeIdentity(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isSurrogate(c)) {
                if (!Character.isHighSurrogate(c) || i + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(i + 1))) return false;
                i++;
                continue;
            }
            int type = Character.getType(c);
            if (type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) return false;
        }
        return value.startsWith("uid:") || value.startsWith("anc:w");
    }
}
