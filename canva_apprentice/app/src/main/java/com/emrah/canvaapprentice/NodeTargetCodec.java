package com.emrah.canvaapprentice;

public final class NodeTargetCodec {
    private static final char SEP = '\u001F';
    private static final int MAX_FIELD_LENGTH = 1024;
    private NodeTargetCodec() {}

    public static String encode(int index, String expectedLabel) {
        return encode(index, expectedLabel, "", "", "");
    }

    public static String encode(int index, String expectedLabel,
                                String expectedClass, String expectedBounds, String expectedFlags) {
        if (index < 0
                || !isCanonicalField(expectedLabel)
                || !isCanonicalField(expectedClass)
                || !isCanonicalField(expectedBounds)
                || !isCanonicalField(expectedFlags)) {
            return "";
        }
        return index + String.valueOf(SEP) + exact(expectedLabel) + SEP +
                exact(expectedClass) + SEP + exact(expectedBounds) + SEP + exact(expectedFlags);
    }

    public static int index(String encoded) {
        String[] p = parts(encoded);
        if (p.length == 0) return -1;
        try { return Integer.parseInt(p[0].trim()); }
        catch (Exception e) { return -1; }
    }

    public static String label(String encoded) { return part(encoded, 1); }
    public static String className(String encoded) { return part(encoded, 2); }
    public static String bounds(String encoded) { return part(encoded, 3); }
    public static String flags(String encoded) { return part(encoded, 4); }

    public static boolean hasStructuralEvidence(String encoded) {
        return !className(encoded).isEmpty() && !bounds(encoded).isEmpty() && !flags(encoded).isEmpty();
    }

    private static String part(String encoded, int index) {
        String[] p = parts(encoded);
        return index < p.length ? p[index] : "";
    }

    private static String[] parts(String encoded) {
        return encoded == null ? new String[0] : encoded.split(String.valueOf(SEP), -1);
    }

    /**
     * Exact-node evidence is identity-bearing data copied from compactForTeacher(). Never
     * normalize it. A padded or control-bearing value may look equivalent to a human while
     * referring to a different teacher-visible row, so fail closed instead of trimming it into
     * a valid target. The internal separator is likewise forbidden inside evidence fields.
     */
    private static boolean isCanonicalField(String value) {
        if (value == null) return true;
        if (value.length() > MAX_FIELD_LENGTH || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == SEP || Character.isISOControl(c)) return false;
        }
        return true;
    }

    private static String exact(String value) {
        return value == null ? "" : value;
    }
}
