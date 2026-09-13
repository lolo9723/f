package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

public final class TeacherRequestAuthorityPublicApiTest {
    @Test public void legacyBoundStructuralReconstructionIsNotPublic() throws Exception {
        Method method = TeacherRequestAuthority.class.getDeclaredMethod(
                "fromBoundStructural", String.class);
        assertFalse(Modifier.isPublic(method.getModifiers()));
    }

    @Test public void legacyBoundStructuralReconstructionFailsClosedEvenForLiveMarker() {
        TeacherRequestAuthority live = TeacherRequestAuthority.begin(
                "legacy-reconstruct", "snapshot-legacy-reconstruct");
        assertTrue(live.isValid());
        assertTrue(live.stillOwnsTransport());

        TeacherRequestAuthority reconstructed =
                TeacherRequestAuthority.fromBoundStructural(live.marker);

        assertFalse(reconstructed.isValid());
        assertFalse(reconstructed.stillOwnsTransport());
        assertTrue(reconstructed.marker.isEmpty());
        assertTrue(reconstructed.executionLeaseToken.isEmpty());
    }
}
