package com.emrah.canvaapprentice;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TeacherBridgeReplyMatchTest {
    @Test public void markerInsideProseDoesNotCountAsTeacherReply() {
        String marker = "CAA1_REPLY_abc123|";
        assertFalse(TeacherBridge.hasReplyLine(
                "The expected marker is " + marker + " but this is only explanatory text.",
                marker
        ));
    }

    @Test public void exactReplyLineMustBeginAtPhysicalColumnZero() {
        String marker = "CAA1_REPLY_abc123|";
        assertTrue(TeacherBridge.hasReplyLine(
                "status\n" + marker + "CLICK_TEXT|Elements|0.99|safe\nfooter",
                marker
        ));
        assertFalse(TeacherBridge.hasReplyLine(
                "status\n   " + marker + "CLICK_TEXT|Elements|0.99|indented\nfooter",
                marker
        ));
        assertFalse(TeacherBridge.hasReplyLine(
                "\t" + marker + "CLICK_TEXT|Elements|0.99|tab-indented",
                marker
        ));
    }

    @Test public void wrongRequestMarkerDoesNotCount() {
        String marker = "CAA1_REPLY_abc123|";
        assertFalse(TeacherBridge.hasReplyLine(
                "CAA1_REPLY_other|CLICK_TEXT|Elements|0.99|stale",
                marker
        ));
    }

    @Test public void emptyMarkerNeverMatches() {
        assertFalse(TeacherBridge.hasReplyLine("anything", ""));
    }
}
