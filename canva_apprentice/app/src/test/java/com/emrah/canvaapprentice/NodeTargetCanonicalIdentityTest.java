package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NodeTargetCanonicalIdentityTest {
    @Test public void validExactNodeEvidenceRoundTrips() {
        String encoded = NodeTargetCodec.encode(
                17,
                "Share",
                "android.widget.Button",
                "10 20 110 70",
                "C-"
        );

        assertEquals(17, NodeTargetCodec.index(encoded));
        assertEquals("Share", NodeTargetCodec.label(encoded));
        assertEquals("android.widget.Button", NodeTargetCodec.className(encoded));
        assertEquals("10 20 110 70", NodeTargetCodec.bounds(encoded));
        assertEquals("C-", NodeTargetCodec.flags(encoded));
        assertTrue(NodeTargetCodec.hasStructuralEvidence(encoded));
    }

    @Test public void internalSeparatorInjectionFailsClosed() {
        String encoded = NodeTargetCodec.encode(
                4,
                "Share\u001F7",
                "android.widget.Button",
                "10 20 110 70",
                "C-"
        );

        assertEquals("", encoded);
        assertEquals(-1, NodeTargetCodec.index(encoded));
        assertFalse(NodeTargetCodec.hasStructuralEvidence(encoded));
    }

    @Test public void controlCharactersInStructuralEvidenceFailClosed() {
        assertEquals("", NodeTargetCodec.encode(
                4,
                "Share",
                "android.widget.Button\nInjected",
                "10 20 110 70",
                "C-"
        ));
        assertEquals("", NodeTargetCodec.encode(
                4,
                "Share",
                "android.widget.Button",
                "10 20\t110 70",
                "C-"
        ));
    }

    @Test public void paddedStructuralEvidenceFailsClosedInsteadOfBeingNormalized() {
        assertEquals("", NodeTargetCodec.encode(
                4,
                " Share",
                "android.widget.Button",
                "10 20 110 70",
                "C-"
        ));
        assertEquals("", NodeTargetCodec.encode(
                4,
                "Share",
                "android.widget.Button ",
                "10 20 110 70",
                "C-"
        ));
        assertEquals("", NodeTargetCodec.encode(
                4,
                "Share",
                "android.widget.Button",
                " 10 20 110 70",
                "C-"
        ));
        assertEquals("", NodeTargetCodec.encode(
                4,
                "Share",
                "android.widget.Button",
                "10 20 110 70",
                "C- "
        ));
    }

    @Test public void negativeIndexFailsClosed() {
        assertEquals("", NodeTargetCodec.encode(
                -1,
                "Share",
                "android.widget.Button",
                "10 20 110 70",
                "C-"
        ));
    }
}
