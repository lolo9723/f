package com.emrah.canvaapprentice;

import static org.junit.Assert.fail;

import org.junit.Test;

public final class TeacherBridgeLegacyTransportApiTest {
    @Test public void markerOnlyTeacherTransportApiIsAbsent() throws Exception {
        try {
            TeacherBridge.class.getDeclaredMethod(
                    "ask", String.class, String.class, TeacherBridge.ReplyCallback.class);
            fail("Marker-only teacher transport must not exist; immutable authority is required.");
        } catch (NoSuchMethodException expected) {
            // Expected: the legacy marker-only transport surface has been removed entirely.
        }
    }
}
