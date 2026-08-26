package com.videofabrikasi.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class KaggleRepairPushTest {
    @Test public void exactVersionedKernelSourceRefIsBuilt() {
        assertEquals("owner/vf-project/7", KaggleRepairPush.sourceRef("Owner", "vf-project", 7));
    }

    @Test public void sourceVersionMustBeConcrete() {
        try {
            KaggleRepairPush.sourceRef("owner", "vf-project", 0);
            fail("Version zero must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("version"));
        }
    }

    @Test public void unsafeSourcePartsAreRejected() {
        String[] bad = {"", "../owner", "owner/name", "owner space", "@owner"};
        for (String value : bad) {
            try {
                KaggleRepairPush.sourceRef(value, "vf-project", 1);
                fail("Unsafe owner should be rejected: " + value);
            } catch (IllegalArgumentException expected) {}
        }
        try {
            KaggleRepairPush.sourceRef("owner", "../vf", 1);
            fail("Unsafe slug should be rejected");
        } catch (IllegalArgumentException expected) {}
    }
}
