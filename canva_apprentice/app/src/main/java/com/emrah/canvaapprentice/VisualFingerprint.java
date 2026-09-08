package com.emrah.canvaapprentice;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import java.io.File;

public final class VisualFingerprint {
    private static final int SIDE = 16;
    private static final int HASH_LENGTH = SIDE * SIDE;
    private VisualFingerprint() {}

    public static String fromFile(File file) {
        if (file == null || !file.exists()) return "";
        Bitmap source = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (source == null) return "";
        Bitmap small = Bitmap.createScaledBitmap(source, SIDE, SIDE, true);
        if (small != source) source.recycle();

        StringBuilder out = new StringBuilder(HASH_LENGTH);
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
        double distance=distanceForExecutionContext(a,b,VisualEvidenceLease.isRuntimeDesignContextCurrent());
        if(distance>=1.0) return distance;
        return VisualRequestContextGuard.currentExecutionAllows(distance,0.0100) ? distance : 1.0;
    }

    /**
     * Fail closed at the asynchronous visual-execution boundary. Even identical
     * screenshots cannot authorize a grounded action after the persisted Canva
     * design identity has rolled over.
     *
     * Fingerprints must also have the exact production shape emitted by fromFile():
     * 16x16 lowercase/uppercase hexadecimal luminance nibbles. Accepting a shorter
     * same-valued string (for example "a" vs "a") would otherwise produce zero
     * distance and could turn truncated/corrupt visual evidence into authorization.
     */
    static double distanceForExecutionContext(String a, String b, boolean designContextCurrent) {
        if (!designContextCurrent) return 1.0;
        if (a == null || b == null || a.length() != HASH_LENGTH || b.length() != HASH_LENGTH) return 1.0;
        long sum = 0;
        for (int i = 0; i < HASH_LENGTH; i++) {
            int x = Character.digit(a.charAt(i),16);
            int y = Character.digit(b.charAt(i),16);
            if (x < 0 || y < 0) return 1.0;
            sum += Math.abs(x-y);
        }
        return sum / (15.0 * HASH_LENGTH);
    }
}
