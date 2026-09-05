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
}
