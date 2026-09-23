package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class DesignAnchorPolicyTest {
    @Test public void acceptsRealDesignName() {
        assertTrue(DesignAnchorPolicy.isPlausible("30 Ağustos Fakülte Afişi"));
    }

    @Test public void rejectsGenericCanvaNavigationLabels() {
        assertFalse(DesignAnchorPolicy.isPlausible("Projects"));
        assertFalse(DesignAnchorPolicy.isPlausible("Projeler"));
        assertFalse(DesignAnchorPolicy.isPlausible("Canva"));
        assertFalse(DesignAnchorPolicy.isPlausible("Create a design"));
    }

    @Test public void rejectsGenericEditorControlsAsPersistentIdentity() {
        assertFalse(DesignAnchorPolicy.isPlausible("Create"));
        assertFalse(DesignAnchorPolicy.isPlausible("Oluştur"));
        assertFalse(DesignAnchorPolicy.isPlausible("Text"));
        assertFalse(DesignAnchorPolicy.isPlausible("Metin"));
        assertFalse(DesignAnchorPolicy.isPlausible("Elements"));
        assertFalse(DesignAnchorPolicy.isPlausible("Öğeler"));
        assertFalse(DesignAnchorPolicy.isPlausible("Uploads"));
        assertFalse(DesignAnchorPolicy.isPlausible("Yüklemeler"));
        assertFalse(DesignAnchorPolicy.isPlausible("Position"));
        assertFalse(DesignAnchorPolicy.isPlausible("Konum"));
        assertFalse(DesignAnchorPolicy.isPlausible("Animate"));
        assertFalse(DesignAnchorPolicy.isPlausible("Canlandır"));
        assertFalse(DesignAnchorPolicy.isPlausible("Present"));
        assertFalse(DesignAnchorPolicy.isPlausible("Sun"));
    }

    @Test public void rejectsGenericDesignTypeLabels() {
        assertFalse(DesignAnchorPolicy.isPlausible("Presentation"));
        assertFalse(DesignAnchorPolicy.isPlausible("Sunum"));
        assertFalse(DesignAnchorPolicy.isPlausible("Instagram post"));
        assertFalse(DesignAnchorPolicy.isPlausible("Poster"));
    }

    @Test public void rejectsDefaultUntitledNamesBecauseTheyAreNotUniqueIdentity() {
        assertFalse(DesignAnchorPolicy.isPlausible("Untitled design"));
        assertFalse(DesignAnchorPolicy.isPlausible("Untitled presentation"));
        assertFalse(DesignAnchorPolicy.isPlausible("Adsız tasarım"));
        assertFalse(DesignAnchorPolicy.isPlausible("Başlıksız sunum"));
    }

    @Test public void rejectsUrlsAsDesignAnchor() {
        assertFalse(DesignAnchorPolicy.isPlausible("https://www.canva.com/design/abc"));
    }

    @Test public void bindingRequiresExactVisibleTitleInsideEditor() {
        assertTrue(DesignAnchorPolicy.mayBindVisibleEditor("30 Ağustos Fakülte Afişi", true, false));
    }

    @Test public void bindingRejectsPlausibleButHallucinatedTitle() {
        assertFalse(DesignAnchorPolicy.mayBindVisibleEditor("30 Ağustos Fakülte Afişi", false, false));
    }

    @Test public void bindingRejectsProjectCardTitleOnCanvaHome() {
        assertFalse(DesignAnchorPolicy.mayBindVisibleEditor("30 Ağustos Fakülte Afişi", true, true));
    }

    @Test public void bindingStillRejectsGenericAnchorEvenWhenVisible() {
        assertFalse(DesignAnchorPolicy.mayBindVisibleEditor("Projects", true, false));
        assertFalse(DesignAnchorPolicy.mayBindVisibleEditor("Text", true, false));
        assertFalse(DesignAnchorPolicy.mayBindVisibleEditor("Öğeler", true, false));
    }
}
