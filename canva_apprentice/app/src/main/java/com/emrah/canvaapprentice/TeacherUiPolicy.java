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
}
