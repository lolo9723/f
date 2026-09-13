package com.emrah.canvaapprentice;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

import static org.junit.Assert.*;

public class TeacherProtocolApiSurfaceTest {
    @Test public void legacyMarkerAndRequestIdApisAreNotPublic() throws Exception {
        Method markerFor = TeacherProtocol.class.getDeclaredMethod("markerFor", String.class);
        Method legacyBuild = TeacherProtocol.class.getDeclaredMethod(
                "buildRequest", TaskState.class, UiTreeSnapshot.class, String.class, String.class);
        Method legacyVisualBuild = TeacherProtocol.class.getDeclaredMethod(
                "buildVisualRequest", TaskState.class, UiTreeSnapshot.class, String.class, String.class);
        Method legacyParse = TeacherProtocol.class.getDeclaredMethod(
                "parse", String.class, String.class);

        assertFalse(Modifier.isPublic(markerFor.getModifiers()));
        assertFalse(Modifier.isPublic(legacyBuild.getModifiers()));
        assertFalse(Modifier.isPublic(legacyVisualBuild.getModifiers()));
        assertFalse(Modifier.isPublic(legacyParse.getModifiers()));
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
}
