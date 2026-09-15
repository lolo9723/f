package com.emrah.canvaapprentice;

import java.util.Locale;

public final class TeacherUiPolicy {
    private TeacherUiPolicy() {}

    public static boolean isExactSendLabel(String raw) {
        if (raw == null || hasUnsafeAccessibilityFormatting(raw)) return false;
        // Fail closed on padding: an exact transport control must expose the exact label,
        // not a look-alike that only becomes trusted after trimming attacker/stale UI data.
        if (!raw.equals(raw.trim())) return false;
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return s.equals("send") ||
                s.equals("send message") ||
                s.equals("send prompt") ||
                s.equals("gönder") ||
                s.equals("mesaj gönder") ||
                s.equals("mesajı gönder");
    }

    private static boolean hasUnsafeAccessibilityFormatting(String raw) {
        for (int offset = 0; offset < raw.length();) {
            char unit = raw.charAt(offset);
            if (Character.isHighSurrogate(unit)) {
                if (offset + 1 >= raw.length() || !Character.isLowSurrogate(raw.charAt(offset + 1))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }

            int codePoint = raw.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
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
     * If Android exposes both text and content-description they must agree that this is a send
     * control; conflicting or structurally unsafe accessibility evidence fails closed instead of
     * trusting one field or normalizing spoofable formatting into an exact label.
     */
    public static boolean isUsableSend(boolean visibleToUser, boolean enabled,
                                       String label, String description) {
        if (!visibleToUser || !enabled) return false;
        String safeLabel = label == null ? "" : label.trim();
        String safeDescription = description == null ? "" : description.trim();
        boolean hasLabel = !safeLabel.isEmpty();
        boolean hasDescription = !safeDescription.isEmpty();
        if (!hasLabel && !hasDescription) return false;
        if (hasLabel && !isExactSendLabel(label)) return false;
        if (hasDescription && !isExactSendLabel(description)) return false;
        return true;
    }
}
