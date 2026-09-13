package com.emrah.canvaapprentice;

import java.util.Locale;

public final class TeacherUiPolicy {
    private TeacherUiPolicy() {}

    public static boolean isExactSendLabel(String raw) {
        if (raw == null) return false;
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return s.equals("send") ||
                s.equals("send message") ||
                s.equals("send prompt") ||
                s.equals("gönder") ||
                s.equals("mesaj gönder") ||
                s.equals("mesajı gönder");
    }

    /**
     * Accessibility trees can retain hidden/stale editors after navigation. Teacher transport
     * must never write into one: it must be current, visible, enabled, and editable.
     */
    public static boolean isUsableEditable(boolean visibleToUser, boolean enabled, boolean editable) {
        return visibleToUser && enabled && editable;
    }

    /**
     * A matching "Send" label is not enough. Hidden/stale buttons are unsafe even when enabled.
     */
    public static boolean isUsableSend(boolean visibleToUser, boolean enabled,
                                       String label, String description) {
        if (!visibleToUser || !enabled) return false;
        return isExactSendLabel(label) || isExactSendLabel(description);
    }
}
