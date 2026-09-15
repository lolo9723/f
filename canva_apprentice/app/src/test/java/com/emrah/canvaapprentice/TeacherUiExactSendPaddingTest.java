package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TeacherUiExactSendPaddingTest {
    @Test public void exactSendLabelsRemainAccepted() {
        assertTrue(TeacherUiPolicy.isExactSendLabel("Send"));
        assertTrue(TeacherUiPolicy.isExactSendLabel("Gönder"));
        assertTrue(TeacherUiPolicy.isUsableSend(true, true, "", "Send"));
    }

    @Test public void leadingOrTrailingPaddingFailsClosed() {
        assertFalse(TeacherUiPolicy.isExactSendLabel(" Send"));
        assertFalse(TeacherUiPolicy.isExactSendLabel("Send "));
        assertFalse(TeacherUiPolicy.isExactSendLabel("\tGönder"));
        assertFalse(TeacherUiPolicy.isExactSendLabel("Gönder\t"));
        assertFalse(TeacherUiPolicy.isUsableSend(true, true, "", " Send "));
    }
}
