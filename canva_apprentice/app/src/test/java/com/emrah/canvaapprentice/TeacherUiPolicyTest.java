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

    @Test public void rejectsControlAndUnicodeFormattingInsideExactSendLabels() {
        assertFalse(TeacherUiPolicy.isExactSendLabel("Send\nmessage"));
        assertFalse(TeacherUiPolicy.isExactSendLabel("Send\tmessage"));
        assertFalse(TeacherUiPolicy.isExactSendLabel("Se\u200Bnd"));
        assertFalse(TeacherUiPolicy.isExactSendLabel("Se\u202End"));
        assertFalse(TeacherUiPolicy.isExactSendLabel("Send\u2028message"));
        assertFalse(TeacherUiPolicy.isExactSendLabel("Send\u2029message"));
        assertFalse(TeacherUiPolicy.isExactSendLabel("\u2066Send\u2069"));
    }

    @Test public void rejectsMalformedUtf16InsideSendAccessibilityEvidence() {
        assertFalse(TeacherUiPolicy.isExactSendLabel("Send\uD800"));
        assertFalse(TeacherUiPolicy.isExactSendLabel("\uDC00Send"));
        assertFalse(TeacherUiPolicy.isExactSendLabel("Mesaj gönder\uDFFF"));
        assertFalse(TeacherUiPolicy.isUsableSend(true, true, "Send\uD800", ""));
        assertFalse(TeacherUiPolicy.isUsableSend(true, true, "Send", "Gönder\uDC00"));
    }

    @Test public void validSupplementaryUnicodeDoesNotBecomeMalformedEvidence() {
        assertFalse(TeacherUiPolicy.isExactSendLabel("Send \uD83D\uDE80"));
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

    @Test public void conflictingSendAccessibilityEvidenceFailsClosed() {
        assertTrue(TeacherUiPolicy.isUsableSend(true, true, "Send", "Send message"));
        assertFalse(TeacherUiPolicy.isUsableSend(true, true, "Cancel", "Send"));
        assertFalse(TeacherUiPolicy.isUsableSend(true, true, "Send", "Share"));
        assertFalse(TeacherUiPolicy.isUsableSend(true, true, "", ""));
        assertFalse(TeacherUiPolicy.isUsableSend(true, true, null, null));
    }

    @Test public void unsafeFormattingInEitherAccessibilityFieldFailsClosed() {
        assertFalse(TeacherUiPolicy.isUsableSend(true, true, "Send", "Send\nmessage"));
        assertFalse(TeacherUiPolicy.isUsableSend(true, true, "Se\u200Bnd", "Send"));
        assertFalse(TeacherUiPolicy.isUsableSend(true, true, "Send", "\u2066Send\u2069"));
    }
}
