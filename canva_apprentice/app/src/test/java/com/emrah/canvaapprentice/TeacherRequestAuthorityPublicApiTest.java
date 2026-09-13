package com.emrah.canvaapprentice;

import static org.junit.Assert.fail;

import org.junit.Test;

public final class TeacherRequestAuthorityPublicApiTest {
    @Test public void legacyBoundStructuralReconstructionIsRemoved() {
        try {
            TeacherRequestAuthority.class.getDeclaredMethod(
                    "fromBoundStructural", String.class);
            fail("legacy marker-based authority reconstruction surface must stay removed");
        } catch (NoSuchMethodException expected) {
            // Immutable authority must be created once for the exact snapshot and carried forward.
        }
    }
}
