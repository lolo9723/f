package com.emrah.canvaapprentice;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
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

    @Test public void reorderedPreDispatchNodeCannotGainAuthorityWhenStableIdentityMatches() {
        String marker = "CAA1_REPLY_abc123|";
        String stale = marker + "NOOP|||1.0|stale";
        String mutatedAfterReorder = marker + "CLICK_TEXT|Elements|0.99|same-old-node";
        Set<String> textBaseline = new HashSet<>();
        textBaseline.add(stale);
        Set<String> nodeBaseline = new HashSet<>();
        nodeBaseline.add("node-42");

        // Occurrence 1 would pass the old prefix-only rule after UI reordering. Exact-node
        // provenance must still reject the node because it existed before dispatch.
        assertFalse(TeacherBridge.isEligiblePostDispatchReplyNode(
                true, mutatedAfterReorder, marker, textBaseline,
                1, 1, "node-42", nodeBaseline));
    }

    @Test public void genuinelyNewStableNodeCanGainAuthorityAfterBaselinePrefix() {
        String marker = "CAA1_REPLY_abc123|";
        String stale = marker + "NOOP|||1.0|stale";
        String fresh = marker + "CLICK_TEXT|Elements|0.99|fresh";
        Set<String> textBaseline = new HashSet<>();
        textBaseline.add(stale);
        Set<String> nodeBaseline = new HashSet<>();
        nodeBaseline.add("node-old");

        assertTrue(TeacherBridge.isEligiblePostDispatchReplyNode(
                true, fresh, marker, textBaseline,
                1, 1, "node-new", nodeBaseline));
    }

    @Test public void structuralAncestryFallbackIsDeterministicAndTextIndependent() {
        String before = TeacherBridge.composeStructuralAncestryIdentity(
                7,
                "android.widget.TextView", "com.openai.chatgpt:id/message_text",
                "android.view.ViewGroup", "com.openai.chatgpt:id/message_container");
        String after = TeacherBridge.composeStructuralAncestryIdentity(
                7,
                "android.widget.TextView", "com.openai.chatgpt:id/message_text",
                "android.view.ViewGroup", "com.openai.chatgpt:id/message_container");

        assertFalse(before.isEmpty());
        assertEquals(before, after);
    }

    @Test public void matchingAncestryFallbackRejectsReorderedMutatedOldNodeWithoutUniqueId() {
        String marker = "CAA1_REPLY_abc123|";
        String stale = marker + "NOOP|||1.0|stale";
        String mutatedAfterReorder = marker + "CLICK_TEXT|Elements|0.99|mutated-old-node";
        String ancestry = TeacherBridge.composeStructuralAncestryIdentity(
                3,
                "android.widget.TextView", "",
                "android.view.ViewGroup", "com.openai.chatgpt:id/message_container");
        Set<String> textBaseline = new HashSet<>();
        textBaseline.add(stale);
        Set<String> nodeBaseline = new HashSet<>();
        nodeBaseline.add(ancestry);

        assertFalse(TeacherBridge.isEligiblePostDispatchReplyNode(
                true, mutatedAfterReorder, marker, textBaseline,
                4, 1, ancestry, nodeBaseline));
    }

    @Test public void ambiguousStableIdentityFailsClosedEvenAfterBaselinePrefix() {
        String marker = "CAA1_REPLY_abc123|";
        String fresh = marker + "CLICK_TEXT|Elements|0.99|ambiguous";
        String ancestry = TeacherBridge.composeStructuralAncestryIdentity(
                9,
                "android.widget.TextView", "",
                "android.view.ViewGroup", "com.openai.chatgpt:id/message_container");

        assertFalse(TeacherBridge.isEligiblePostDispatchReplyNode(
                true, fresh, marker, new HashSet<>(),
                2, 1, ancestry, new HashSet<>(), 2));
    }

    @Test public void uniqueCurrentStableIdentityCanStillProveFreshReply() {
        String marker = "CAA1_REPLY_abc123|";
        String fresh = marker + "CLICK_TEXT|Elements|0.99|unique";
        String ancestry = TeacherBridge.composeStructuralAncestryIdentity(
                9,
                "android.widget.TextView", "",
                "android.view.ViewGroup", "com.openai.chatgpt:id/message_container");

        assertTrue(TeacherBridge.isEligiblePostDispatchReplyNode(
                true, fresh, marker, new HashSet<>(),
                1, 1, ancestry, new HashSet<>(), 1));
    }

    @Test public void malformedStructuralAncestryCannotPretendToBeStableIdentity() {
        assertEquals("", TeacherBridge.composeStructuralAncestryIdentity(1,
                "android.widget.TextView", "only-one-level"));
        assertEquals("", TeacherBridge.composeStructuralAncestryIdentity(-1,
                "android.widget.TextView", "id", "android.view.ViewGroup", "parent"));
        assertEquals("", TeacherBridge.composeStructuralAncestryIdentity(1,
                "", "", "android.view.ViewGroup", "parent"));
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
