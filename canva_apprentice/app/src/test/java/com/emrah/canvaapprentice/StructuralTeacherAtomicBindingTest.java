package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Regression guard: a structural teacher request must never expose a marker/lease pair
 * before the exact teacher-visible snapshot is available. */
public final class StructuralTeacherAtomicBindingTest {
    @Before public void setUp() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @Test public void markerFormattingDoesNotPublishPartialAuthority() {
        String marker = TeacherProtocol.markerFor("atomicstruct1");

        assertEquals("CAA1_REPLY_atomicstruct1|", marker);
        assertEquals("", TeacherExecutionLease.currentGlobalToken());
        assertEquals(0, CheckpointRequestGuard.pendingRequestCountForTest());
        assertFalse(CheckpointRequestGuard.currentBoundRequestLease(marker).checkpointCurrent);
    }

    @Test public void structuralBuildUsesAtomicAuthorityInsteadOfSplitBinding() throws Exception {
        String source = source("TeacherProtocol.java");
        String build = section(source,
                "public static String buildRequest(",
                "public static String buildVisualRequest(");

        assertTrue("structural build must create the complete immutable authority tuple",
                build.contains("TeacherRequestAuthority.begin("));
        assertTrue("invalid atomic authority must fail closed before transport",
                build.contains("!authority.isValid() || !authority.stillOwnsTransport()"));
        assertFalse("structural build must not use legacy late snapshot binding",
                build.contains("CheckpointRequestGuard.bindSnapshot("));
    }

    private static String source(String fileName) throws Exception {
        Path cursor = Paths.get("").toAbsolutePath().normalize();
        while (cursor != null) {
            Path direct = cursor.resolve("app/src/main/java/com/emrah/canvaapprentice/" + fileName);
            if (Files.isRegularFile(direct)) {
                return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
            }
            Path nested = cursor.resolve("canva_apprentice/app/src/main/java/com/emrah/canvaapprentice/" + fileName);
            if (Files.isRegularFile(nested)) {
                return new String(Files.readAllBytes(nested), StandardCharsets.UTF_8);
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError(fileName + " source must be available to the safety test");
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = start < 0 ? -1 : source.indexOf(endMarker, start);
        assertTrue("missing section start", start >= 0);
        assertTrue("missing section end", end > start);
        return source.substring(start, end);
    }
}
