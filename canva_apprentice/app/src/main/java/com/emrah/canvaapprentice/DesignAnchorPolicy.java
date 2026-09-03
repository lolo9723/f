package com.emrah.canvaapprentice;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public final class DesignAnchorPolicy {
    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
            "canva","home","ana sayfa","projects","projeler","templates","sablonlar",
            "share","paylas","create a design","tasarim olustur","menu","menu",
            "undo","geri al","redo","yinele","file","dosya","settings","ayarlar",
            "download","indir","save","kaydet"
    ));

    private DesignAnchorPolicy() {}

    public static boolean isPlausible(String anchor) {
        if (anchor == null) return false;
        String a = anchor.trim();
        if (a.length() < 2 || a.length() > 140) return false;
        String n = normalize(a);
        if (GENERIC.contains(n)) return false;
        if (n.startsWith("http://") || n.startsWith("https://")) return false;
        return true;
    }

    private static String normalize(String s) {
        String x = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}","")
                .toLowerCase(Locale.ROOT)
                .replace('ı','i');
        return x.replaceAll("\\s+"," ").trim();
    }
}
