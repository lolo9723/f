package com.emrah.canvaapprentice;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TeacherReplyIdentitylessAmbiguityTest {
    @Test public void identitylessFallbackAcceptsExactlyOneNewMarkerNode() {
        String marker = "CAA1_REPLY_abc123|";
        String oldReply = marker + "NOOP|||1.0|old";
        String freshReply = marker + "CLICK_TEXT|Elements|0.99|fresh";
        Set<String> baseline = new HashSet<>();
        baseline.add(oldReply);

        assertTrue(TeacherBridge.isEligiblePostDispatchReplyNode(
                true, freshReply, marker, baseline,
                1, 1, "", new HashSet<>(), 0, 2));
    }

    @Test public void identitylessFallbackRejectsMultipleNewMarkerNodesAsAmbiguous() {
        String marker = "CAA1_REPLY_abc123|";
        String oldReply = marker + "NOOP|||1.0|old";
        String firstCandidate = marker + "CLICK_TEXT|Elements|0.99|candidate-one";
        String secondCandidate = marker + "CLICK_TEXT|Text|0.99|candidate-two";
        Set<String> baseline = new HashSet<>();
        baseline.add(oldReply);

        assertFalse(TeacherBridge.isEligiblePostDispatchReplyNode(
                true, firstCandidate, marker, baseline,
                1, 1, "", new HashSet<>(), 0, 3));
        assertFalse(TeacherBridge.isEligiblePostDispatchReplyNode(
                true, secondCandidate, marker, baseline,
                2, 1, "", new HashSet<>(), 0, 3));
    }

    @Test public void identitylessFallbackRejectsCandidateOutsideExactBaselineBoundary() {
        String marker = "CAA1_REPLY_abc123|";
        String freshReply = marker + "CLICK_TEXT|Elements|0.99|fresh";

        assertFalse(TeacherBridge.isEligiblePostDispatchReplyNode(
                true, freshReply, marker, new HashSet<>(),
                1, 0, "", new HashSet<>(), 0, 1));
    }

    @Test public void stableUniqueIdentityStillWorksWhenOtherMarkerNodesExist() {
        String marker = "CAA1_REPLY_abc123|";
        String freshReply = marker + "CLICK_TEXT|Elements|0.99|fresh-stable";

        assertTrue(TeacherBridge.isEligiblePostDispatchReplyNode(
                true, freshReply, marker, new HashSet<>(),
                2, 1, "uid:new-node", new HashSet<>(), 1, 4));
    }
}
