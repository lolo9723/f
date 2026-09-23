package com.emrah.canvaapprentice;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public final class DesignAnchorPolicy {
    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
            "canva","home","ana sayfa","projects","projeler","templates","sablonlar",
            "share","paylas","create","olustur","create a design","tasarim olustur","menu","menu",
            "undo","geri al","redo","yinele","file","dosya","settings","ayarlar",
            "download","indir","save","kaydet","open","ac","edit","duzenle","add","ekle",
            "text","metin","elements","ogeler","uploads","yuklemeler","apps","uygulamalar",
            "position","konum","animate","canlandir","present","sun",
            "design","tasarim","presentation","sunum","whiteboard","beyaz tahta",
            "document","dokuman","doc","video","poster","afis","flyer","brosur",
            "logo","resume","ozgecmis","cv","website","web sitesi","instagram post",
            "instagram gonderisi","facebook post","facebook gonderisi","mobile video",
            "mobil video"
    ));

    private DesignAnchorPolicy() {}

    public static boolean isPlausible(String anchor) {
        if (anchor == null) return false;
        String a = anchor.trim();
        if (a.length() < 2 || a.length() > 140) return false;
        String n = normalize(a);
        if (GENERIC.contains(n)) return false;
        if (n.equals("untitled") || n.startsWith("untitled ")
                || n.equals("adsiz") || n.startsWith("adsiz ")
                || n.equals("basliksiz") || n.startsWith("basliksiz ")) return false;
        if (n.startsWith("http://") || n.startsWith("https://")) return false;
        return true;
    }

    /**
     * Initial identity binding requires two independent facts from the live accessibility tree:
     * the exact plausible title and editor-context evidence. Merely being "not home" is not
     * positive editor evidence (dialogs/loading/project-detail surfaces can also be not-home).
     */
    public static boolean mayBindVisibleEditor(String anchor, boolean exactAnchorVisible,
                                                boolean canvaHomeVisible, boolean editorContextVisible) {
        return isPlausible(anchor) && exactAnchorVisible && !canvaHomeVisible && editorContextVisible;
    }

    private static String normalize(String s) {
        String x = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}","")
                .toLowerCase(Locale.ROOT)
                .replace('ı','i');
        return x.replaceAll("\\s+"," ").trim();
    }
}
