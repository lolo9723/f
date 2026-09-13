package com.emrah.canvaapprentice;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

import static org.junit.Assert.*;

public class TeacherProtocolApiSurfaceTest {
    @Test public void legacyMarkerParserApisAreRemoved() {
        assertMissing("parse", String.class, String.class);
        assertMissing("parse", String.class, String.class, boolean.class);
    }

    @Test public void markerCreationRemainsNonPublicCompatibilityOnly() throws Exception {
        Method markerFor = TeacherProtocol.class.getDeclaredMethod("markerFor", String.class);
        assertFalse(Modifier.isPublic(markerFor.getModifiers()));
    }

    @Test public void requestIdPromptBuildersAreRemoved() {
        assertMissing("buildRequest",
                TaskState.class, UiTreeSnapshot.class, String.class, String.class);
        assertMissing("buildVisualRequest",
                TaskState.class, UiTreeSnapshot.class, String.class, String.class);
    }

    @Test public void immutableAuthorityApisRemainPublic() throws Exception {
        Method build = TeacherProtocol.class.getDeclaredMethod(
                "buildRequest", TaskState.class, UiTreeSnapshot.class, String.class, TeacherRequestAuthority.class);
        Method visualBuild = TeacherProtocol.class.getDeclaredMethod(
                "buildVisualRequest", TaskState.class, UiTreeSnapshot.class, TeacherRequestAuthority.class, String.class);
        Method parse = TeacherProtocol.class.getDeclaredMethod(
                "parse", String.class, TeacherRequestAuthority.class);

        assertTrue(Modifier.isPublic(build.getModifiers()));
        assertTrue(Modifier.isPublic(visualBuild.getModifiers()));
        assertTrue(Modifier.isPublic(parse.getModifiers()));
    }

    private static void assertMissing(String name, Class<?>... parameterTypes) {
        try {
            TeacherProtocol.class.getDeclaredMethod(name, parameterTypes);
            fail("legacy teacher protocol surface must stay removed: " + name);
        } catch (NoSuchMethodException expected) {
            // Expected: prompt creation and reply parsing must carry immutable TeacherRequestAuthority.
        }
    }
}
