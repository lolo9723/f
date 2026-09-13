package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TeacherUiPolicyTest {
    @Test public void acceptsOnlyKnownSendLabels() {
        assertTrue(TeacherUiPolicy.isExactSendLabel("Send"));
        assertTrue(TeacherUiPolicy.isExactSendLabel("Send message"));
        assertTrue(TeacherUiPolicy.isExactSendLabel("Gönder"));
        assertTrue(TeacherUiPolicy.isExactSendLabel("Mesaj gönder"));
    }

    @Test public void rejectsSubstringAndUnrelatedSendControls() {
        assertFalse(TeacherUiPolicy.isExactSendLabel("Send feedback"));
        assertFalse(TeacherUiPolicy.isExactSendLabel("Resend"));
        assertFalse(TeacherUiPolicy.isExactSendLabel("Gönderilenler"));
        assertFalse(TeacherUiPolicy.isExactSendLabel("Send to another app"));
        assertFalse(TeacherUiPolicy.isExactSendLabel(""));
        assertFalse(TeacherUiPolicy.isExactSendLabel(null));
    }

    @Test public void hiddenOrDisabledEditorsAreNeverUsable() {
        assertTrue(TeacherUiPolicy.isUsableEditable(true, true, true));
        assertFalse(TeacherUiPolicy.isUsableEditable(false, true, true));
        assertFalse(TeacherUiPolicy.isUsableEditable(true, false, true));
        assertFalse(TeacherUiPolicy.isUsableEditable(true, true, false));
    }

    @Test public void hiddenOrDisabledSendNodesAreNeverUsableEvenWithExactLabel() {
        assertTrue(TeacherUiPolicy.isUsableSend(true, true, "Send", ""));
        assertTrue(TeacherUiPolicy.isUsableSend(true, true, "", "Mesaj gönder"));
        assertFalse(TeacherUiPolicy.isUsableSend(false, true, "Send", ""));
        assertFalse(TeacherUiPolicy.isUsableSend(true, false, "Send", ""));
        assertFalse(TeacherUiPolicy.isUsableSend(true, true, "Send feedback", ""));
    }
}
