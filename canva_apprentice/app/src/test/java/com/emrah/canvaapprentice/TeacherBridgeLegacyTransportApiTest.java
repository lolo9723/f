package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

public final class TeacherBridgeLegacyTransportApiTest {
    @Test public void markerOnlyTeacherTransportIsNotPublic() throws Exception {
        Method method = TeacherBridge.class.getDeclaredMethod(
                "ask", String.class, String.class, TeacherBridge.ReplyCallback.class);
        assertFalse(Modifier.isPublic(method.getModifiers()));
    }
}
