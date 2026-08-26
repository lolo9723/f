package com.videofabrikasi.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class KaggleClientTest {
    @Test public void slugifyIsStableAndSafe() {
        assertEquals("iki-mektup-cok-kotu-haber", KaggleClient.slugify("İki mektup: Çok kötü haber!"));
        assertFalse(KaggleClient.slugify("////").isEmpty());
        assertTrue(KaggleClient.slugify("abcdefghijklmnopqrstuvwxyz0123456789-abcdef").length() <= 38);
    }

    @Test public void statusNormalizationWorks() {
        assertEquals("TAMAMLANDI", KaggleClient.normalizeStatus("COMPLETE"));
        assertEquals("ÜRETİLİYOR", KaggleClient.normalizeStatus("RUNNING"));
        assertEquals("KUYRUKTA", KaggleClient.normalizeStatus("QUEUED"));
        assertEquals("HATALI", KaggleClient.normalizeStatus("ERROR"));
    }
}
