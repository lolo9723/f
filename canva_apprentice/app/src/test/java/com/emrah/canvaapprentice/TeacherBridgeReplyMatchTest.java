package com.emrah.canvaapprentice;

import java.util.HashSet;
import java.util.Set;
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

    @Test public void hiddenAccessibilityNodeCannotBecomeTeacherReplyAuthority() {
        String marker = "CAA1_REPLY_abc123|";
        String reply = marker + "CLICK_TEXT|Elements|0.99|safe";

        assertFalse(TeacherBridge.isEligibleReplyNode(false, reply, marker));
        assertTrue(TeacherBridge.isEligibleReplyNode(true, reply, marker));
    }

    @Test public void replyAlreadyVisibleBeforeDispatchCannotBecomeCurrentReply() {
        String marker = "CAA1_REPLY_abc123|";
        String staleVisibleReply = marker + "CLICK_TEXT|Elements|0.99|stale";
        Set<String> baseline = new HashSet<>();
        baseline.add(staleVisibleReply);

        assertFalse(TeacherBridge.isEligibleReplyNode(
                true, staleVisibleReply, marker, baseline));
    }

    @Test public void newlyAppearedReplyAfterDispatchRemainsEligible() {
        String marker = "CAA1_REPLY_abc123|";
        String oldReply = marker + "NOOP|||1.0|old";
        String newReply = marker + "CLICK_TEXT|Elements|0.99|new";
        Set<String> baseline = new HashSet<>();
        baseline.add(oldReply);

        assertTrue(TeacherBridge.isEligibleReplyNode(
                true, newReply, marker, baseline));
    }

    @Test public void mutatedPreDispatchNodeCannotGainAuthorityFromChangedText() {
        String marker = "CAA1_REPLY_abc123|";
        String beforeDispatch = marker + "NOOP|||1.0|stale";
        String sameNodeAfterMutation = marker + "CLICK_TEXT|Elements|0.99|mutated-stale-node";
        Set<String> baseline = new HashSet<>();
        baseline.add(beforeDispatch);

        // Exact-text protection alone would consider the changed value new. Structural
        // occurrence provenance must still reject occurrence zero because it existed before send.
        assertTrue(TeacherBridge.isEligibleReplyNode(
                true, sameNodeAfterMutation, marker, baseline));
        assertFalse(TeacherBridge.isEligiblePostDispatchReplyNode(
                true, sameNodeAfterMutation, marker, baseline, 0, 1));
    }

    @Test public void replyAppendedAfterPreDispatchMarkerPrefixCanGainAuthority() {
        String marker = "CAA1_REPLY_abc123|";
        String stale = marker + "NOOP|||1.0|stale";
        String appended = marker + "CLICK_TEXT|Elements|0.99|fresh";
        Set<String> baseline = new HashSet<>();
        baseline.add(stale);

        assertTrue(TeacherBridge.isEligiblePostDispatchReplyNode(
                true, appended, marker, baseline, 1, 1));
        assertFalse(TeacherBridge.isEligiblePostDispatchReplyNode(
                false, appended, marker, baseline, 1, 1));
    }

    @Test public void malformedOccurrenceMetadataFailsClosed() {
        String marker = "CAA1_REPLY_abc123|";
        String reply = marker + "NOOP|||1.0|reply";

        assertFalse(TeacherBridge.isEligiblePostDispatchReplyNode(
                true, reply, marker, new HashSet<>(), -1, 0));
        assertFalse(TeacherBridge.isEligiblePostDispatchReplyNode(
                true, reply, marker, new HashSet<>(), 0, -1));
    }
}
