package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

public final class TeacherRequestAuthorityPublicApiTest {
    @Test public void legacyBoundStructuralReconstructionIsNotPublic() throws Exception {
        Method method = TeacherRequestAuthority.class.getDeclaredMethod(
                "fromBoundStructural", String.class);
        assertFalse(Modifier.isPublic(method.getModifiers()));
    }
}
