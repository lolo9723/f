package com.emrah.canvaapprentice;

public final class NodeTargetCodec {
    private static final char SEP = '\u001F';
    private NodeTargetCodec() {}

    public static String encode(int index, String expectedLabel) {
        return encode(index, expectedLabel, "", "", "");
    }

    public static String encode(int index, String expectedLabel,
                                String expectedClass, String expectedBounds, String expectedFlags) {
        return index + String.valueOf(SEP) + clean(expectedLabel) + SEP +
                clean(expectedClass) + SEP + clean(expectedBounds) + SEP + clean(expectedFlags);
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

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace(String.valueOf(SEP), " ").trim();
    }
}
