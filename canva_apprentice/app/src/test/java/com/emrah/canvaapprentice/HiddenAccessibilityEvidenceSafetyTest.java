package com.emrah.canvaapprentice;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HiddenAccessibilityEvidenceSafetyTest {
    private static UiTreeSnapshot.Node node(String text, boolean visible) {
        return new UiTreeSnapshot.Node(
                "", "android.view.View", text, "", null,
                true, false, false, true, visible);
    }

    @Test public void hiddenDesignAnchorCannotProveContinuity() {
        UiTreeSnapshot snapshot = new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                Arrays.asList(node("Existing Design", false)),
                0L);

        assertFalse(snapshot.containsText("Existing Design"));
    }

    @Test public void hiddenHomeLabelsCannotTriggerHomeDetection() {
        UiTreeSnapshot snapshot = new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                Arrays.asList(
                        node("Create a design", false),
                        node("Projects", false),
                        node("Templates", false)),
                0L);

        assertFalse(snapshot.looksLikeCanvaHome());
    }

    @Test public void hiddenNodesAreNotSentToTeacherOrFingerprintAuthority() {
        UiTreeSnapshot visibleOnly = new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                Arrays.asList(node("Visible target", true)),
                0L);
        UiTreeSnapshot withHiddenNoise = new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                Arrays.asList(node("Visible target", true), node("Hidden stale target", false)),
                0L);

        assertFalse(withHiddenNoise.compactForTeacher().contains("Hidden stale target"));
        assertEquals(visibleOnly.stableFingerprint(), withHiddenNoise.stableFingerprint());
    }

    @Test public void hiddenSensitiveTextDoesNotCauseFalseTakeoverButVisibleDoes() {
        UiTreeSnapshot hidden = new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                Arrays.asList(node("verification code", false)),
                0L);
        UiTreeSnapshot visible = new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                Arrays.asList(node("verification code", true)),
                0L);

        assertFalse(hidden.containsSensitiveInput());
        assertTrue(visible.containsSensitiveInput());
    }
}