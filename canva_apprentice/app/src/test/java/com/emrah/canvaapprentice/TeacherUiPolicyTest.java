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
}
