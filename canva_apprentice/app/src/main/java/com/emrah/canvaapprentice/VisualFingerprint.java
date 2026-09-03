package com.emrah.canvaapprentice;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import java.io.File;

public final class VisualFingerprint {
    private static final int SIDE = 16;
    private VisualFingerprint() {}

    public static String fromFile(File file) {
        if (file == null || !file.exists()) return "";
        Bitmap source = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (source == null) return "";
        Bitmap small = Bitmap.createScaledBitmap(source, SIDE, SIDE, true);
        if (small != source) source.recycle();

        StringBuilder out = new StringBuilder(SIDE * SIDE);
        for (int y = 0; y < SIDE; y++) {
            for (int x = 0; x < SIDE; x++) {
                int c = small.getPixel(x,y);
                int lum = (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000;
                int q = Math.max(0, Math.min(15, lum / 16));
                out.append(Integer.toHexString(q));
            }
        }
        small.recycle();
        return out.toString();
    }

    public static double distance(String a, String b) {
        if (a == null || b == null || a.length() != b.length() || a.isEmpty()) return 1.0;
        long sum = 0;
        for (int i = 0; i < a.length(); i++) {
            int x = Character.digit(a.charAt(i),16);
            int y = Character.digit(b.charAt(i),16);
            if (x < 0 || y < 0) return 1.0;
            sum += Math.abs(x-y);
        }
        return sum / (15.0 * a.length());
    }
}
