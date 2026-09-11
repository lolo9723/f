package com.emrah.canvaapprentice;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TeacherVisibleFingerprintCoverageTest {
    @Test public void lateTeacherVisibleNodeChangesFingerprint() {
        UiTreeSnapshot before = snapshotWithLabelAt(150, "Old target");
        UiTreeSnapshot after = snapshotWithLabelAt(150, "New target");

        assertTrue(before.compactForTeacher().contains("150|android.widget.TextView|Old target|"));
        assertTrue(after.compactForTeacher().contains("150|android.widget.TextView|New target|"));
        assertNotEquals(before.stableFingerprint(), after.stableFingerprint());
    }

    @Test public void hiddenNodesDoNotConsumeFingerprintHorizon() {
        List<UiTreeSnapshot.Node> nodes = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            nodes.add(node("hidden-" + i, false));
        }
        for (int i = 0; i < 160; i++) {
            nodes.add(node(i == 150 ? "Old target" : "visible-" + i, true));
        }
        UiTreeSnapshot before = new UiTreeSnapshot(AgentConstants.CANVA_PACKAGE, nodes, 0L);

        List<UiTreeSnapshot.Node> changed = new ArrayList<>(nodes);
        changed.set(130 + 150, node("New target", true));
        UiTreeSnapshot after = new UiTreeSnapshot(AgentConstants.CANVA_PACKAGE, changed, 0L);

        assertNotEquals(before.stableFingerprint(), after.stableFingerprint());
    }

    private static UiTreeSnapshot snapshotWithLabelAt(int changedIndex, String label) {
        List<UiTreeSnapshot.Node> nodes = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            nodes.add(node(i == changedIndex ? label : "node-" + i, true));
        }
        return new UiTreeSnapshot(AgentConstants.CANVA_PACKAGE, nodes, 0L);
    }

    private static UiTreeSnapshot.Node node(String text, boolean visible) {
        return new UiTreeSnapshot.Node(
                "", "android.widget.TextView", text, "", null,
                true, false, false, true, visible);
    }
}
