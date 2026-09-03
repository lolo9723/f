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

    @Test public void rejectsUrlsAsDesignAnchor() {
        assertFalse(DesignAnchorPolicy.isPlausible("https://www.canva.com/design/abc"));
    }
}
