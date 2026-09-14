package com.emrah.canvaapprentice;

import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Method;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public final class TeacherReplyEvidenceSinglePassInvariantTest {
    @Test public void splitLiveReplyMeasurementHelpersStayRemoved() throws Exception {
        assertMissing("visibleReplyNodeCount");
        assertMissing("visibleReplyNodeIdentities");
        assertMissing("visibleReplyNodeIdentityCounts");

        Method capture = TeacherBridge.class.getDeclaredMethod(
                "captureReplyEvidence", AccessibilityNodeInfo.class, String.class);
        assertNotNull(capture);
    }

    private static void assertMissing(String name) {
        for (Method method : TeacherBridge.class.getDeclaredMethods()) {
            if (name.equals(method.getName())) {
                fail(name + " must remain removed; reply provenance must come from one evidence pass.");
            }
        }
    }
}
