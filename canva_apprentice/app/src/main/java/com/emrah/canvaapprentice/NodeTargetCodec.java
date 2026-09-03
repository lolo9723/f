package com.emrah.canvaapprentice;

public final class NodeTargetCodec {
    private static final char SEP = '\u001F';
    private NodeTargetCodec() {}

    public static String encode(int index, String expectedLabel) {
        return index + String.valueOf(SEP) + (expectedLabel == null ? "" : expectedLabel.trim());
    }

    public static int index(String encoded) {
        if (encoded == null) return -1;
        int p = encoded.indexOf(SEP);
        String x = p < 0 ? encoded : encoded.substring(0,p);
        try { return Integer.parseInt(x.trim()); }
        catch (Exception e) { return -1; }
    }

    public static String label(String encoded) {
        if (encoded == null) return "";
        int p = encoded.indexOf(SEP);
        return p < 0 ? "" : encoded.substring(p+1);
    }
}
