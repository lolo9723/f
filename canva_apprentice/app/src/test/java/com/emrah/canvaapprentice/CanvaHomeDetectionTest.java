package com.emrah.canvaapprentice;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CanvaHomeDetectionTest {
    private static UiTreeSnapshot.Node label(String text) {
        return new UiTreeSnapshot.Node("", "android.view.View", text, "", null,
                false, false, false, true);
    }

    @Test public void createDesignAloneIsDecisiveHomeSignal() {
        UiTreeSnapshot snap = new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                Collections.singletonList(label("Create a design")),
                0L);
        assertTrue(snap.looksLikeCanvaHome());
    }

    @Test public void turkishCreateDesignAloneIsDecisiveHomeSignal() {
        UiTreeSnapshot snap = new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                Collections.singletonList(label("Tasarım oluştur")),
                0L);
        assertTrue(snap.looksLikeCanvaHome());
    }

    @Test public void oneWeakNavigationLabelDoesNotMisclassifyEditor() {
        UiTreeSnapshot snap = new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                Collections.singletonList(label("Projects")),
                0L);
        assertFalse(snap.looksLikeCanvaHome());
    }

    @Test public void twoIndependentNavigationLabelsStillIdentifyHome() {
        UiTreeSnapshot snap = new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                Arrays.asList(label("Projects"), label("Templates")),
                0L);
        assertTrue(snap.looksLikeCanvaHome());
    }
}