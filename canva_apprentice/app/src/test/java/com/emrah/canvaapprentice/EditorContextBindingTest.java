package com.emrah.canvaapprentice;

import android.graphics.Rect;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class EditorContextBindingTest {
    private UiTreeSnapshot.Node node(String text) {
        return new UiTreeSnapshot.Node("", "android.view.View", text, "", new Rect(0,0,10,10), true, false, false, true);
    }

    @Test public void bindingRejectsExactTitleWithoutPositiveEditorEvidence() {
        assertFalse(DesignAnchorPolicy.mayBindVisibleEditor("Mevcut Afiş", true, false, false));
        assertTrue(DesignAnchorPolicy.mayBindVisibleEditor("Mevcut Afiş", true, false, true));
    }

    @Test public void editorContextRequiresTwoDistinctEditorControls() {
        UiTreeSnapshot one = new UiTreeSnapshot("com.canva.editor", Arrays.asList(node("Undo")), 1L);
        UiTreeSnapshot two = new UiTreeSnapshot("com.canva.editor", Arrays.asList(node("Undo"), node("Elements")), 1L);
        assertFalse(one.hasCanvaEditorContext());
        assertTrue(two.hasCanvaEditorContext());
    }

    @Test public void homeSurfaceNeverCountsAsEditorContext() {
        UiTreeSnapshot home = new UiTreeSnapshot("com.canva.editor", Arrays.asList(
                node("Create a design"), node("Projects"), node("Undo"), node("Elements")), 1L);
        assertTrue(home.looksLikeCanvaHome());
        assertFalse(home.hasCanvaEditorContext());
    }

    @Test public void turkishEditorControlsProvideIndependentEvidence() {
        UiTreeSnapshot editor = new UiTreeSnapshot("com.canva.editor", Arrays.asList(node("Geri al"), node("Öğeler")), 1L);
        assertTrue(editor.hasCanvaEditorContext());
    }
}
