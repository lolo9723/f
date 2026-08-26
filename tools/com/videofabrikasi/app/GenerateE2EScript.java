package com.videofabrikasi.app;

/** Emits the exact production Python script used by the Android app for a fixed Turkish E2E story. */
public final class GenerateE2EScript {
    private static final String STORY =
            "İki beyaz mektup aynı kişiye gidiyor. Biri iyi haber taşıyor ve özgüvenli, "
                    + "diğeri kötü haber taşıyor ve panik içinde. Mutlu mektup posta kutusuna "
                    + "girmek isterken kötü haber mektubu çığlık atarak arkasından yetişip onu "
                    + "kutuya iter. Kişi önce kötü haberi okuyunca çöker ve iyi haberi açmadan "
                    + "yere düşürür. Gizli nitelik davranıştan finalden önce sezilmelidir.";

    private GenerateE2EScript() {}

    public static void main(String[] args) {
        if (args.length != 1 || args[0].trim().isEmpty()) {
            throw new IllegalArgumentException("Expected exactly one Kaggle kernel slug argument");
        }
        System.out.print(VideoFactoryScript.build(STORY, args[0].trim()));
    }
}
