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
        if (value.startsWith("uid:")) return value.length() > 4;
        return isCanonicalAncestryIdentity(value);
    }

    private static boolean isCanonicalAncestryIdentity(String value) {
        if (!value.startsWith("anc:w")) return false;
        int p = 5;
        int windowStart = p;
        while (p < value.length() && Character.isDigit(value.charAt(p))) p++;
        if (p == windowStart || p >= value.length() || value.charAt(p) != '|') return false;
        if (!isCanonicalNonNegativeInt(value, windowStart, p)) return false;

        int fieldCount = 0;
        boolean previousFieldEmpty = false;
        while (p < value.length()) {
            if (value.charAt(p++) != '|') return false;
            int lenStart = p;
            while (p < value.length() && Character.isDigit(value.charAt(p))) p++;
            if (p == lenStart || p >= value.length() || value.charAt(p++) != ':') return false;
            if (!isCanonicalNonNegativeInt(value, lenStart, p - 1)) return false;
            final int declaredLength;
            try {
                declaredLength = Integer.parseInt(value.substring(lenStart, p - 1));
            } catch (NumberFormatException ex) {
                return false;
            }
            if (declaredLength < 0 || declaredLength > value.length() - p) return false;
            p += declaredLength;
            fieldCount++;
            boolean currentFieldEmpty = declaredLength == 0;
            if (fieldCount % 2 == 1) {
                previousFieldEmpty = currentFieldEmpty;
            } else if (previousFieldEmpty && currentFieldEmpty) {
                // composeStructuralAncestryIdentity never emits a class/view-id pair
                // with both fields empty; accepting one would make revalidation non-canonical.
                return false;
            }
            if (p < value.length() && value.charAt(p) != '|') return false;
        }
        return fieldCount >= 4 && fieldCount % 2 == 0;
    }

    private static boolean isCanonicalNonNegativeInt(String value, int start, int endExclusive) {
        if (start < 0 || endExclusive <= start || endExclusive > value.length()) return false;
        if (endExclusive - start > 1 && value.charAt(start) == '0') return false;
        try {
            return Integer.parseInt(value.substring(start, endExclusive)) >= 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
