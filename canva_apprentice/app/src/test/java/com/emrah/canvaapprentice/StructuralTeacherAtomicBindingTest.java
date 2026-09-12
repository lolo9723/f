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

/** Regression guard for end-to-end immutable structural teacher authority. */
public final class StructuralTeacherAtomicBindingTest {
    @Before public void setUp() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @Test public void productionStructuralPathCarriesOneAuthorityFromSnapshotToParser() throws Exception {
        String source = source("AgentAccessibilityService.java");
        String cycle = section(source,
                "private void runCanvaCycle(String cycleNote)",
                "private void waitForCanvaAndHandle(");

        int begin = cycle.indexOf("TeacherRequestAuthority.begin(");
        int build = cycle.indexOf("TeacherProtocol.buildRequest(");
        int ask = cycle.indexOf("teacher.ask(prompt,structuralAuthority");
        int parse = cycle.indexOf("TeacherProtocol.parse(reply, structuralAuthority)");
        assertTrue("structural authority must be created from the captured snapshot", begin >= 0);
        assertTrue("authority must exist before prompt construction", build > begin);
        assertTrue("the same immutable authority must be sent through TeacherBridge", ask > build);
        assertTrue("the same immutable authority must reach the parser boundary", parse > ask);
        assertFalse("production structural flow must not create a separate marker authority",
                cycle.contains("TeacherProtocol.markerFor(requestId)"));
        assertTrue("transport ownership must be revalidated before parsing",
                cycle.contains("!structuralAuthority.stillOwnsTransport()"));
        assertTrue("parsed execution lease must match the immutable request authority",
                cycle.contains("!structuralAuthority.executionLeaseToken.equals(action.executionLeaseToken)"));
        assertTrue("post-teacher UI drift check must use the authority's exact snapshot",
                cycle.contains("structuralAuthority.snapshotFingerprint"));
    }

    @Test public void authorityParserPreservesItsExactExecutionLease() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin("atomicstruct1", "snapshot-A");
        assertTrue(authority.isValid());

        AgentAction action = TeacherProtocol.parse(
                authority.marker + "NOOP|||1.0|safe-noop", authority);

        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(authority.executionLeaseToken, action.executionLeaseToken);
    }

    @Test public void staleAuthorityCannotBorrowANewerExecutionLeaseAtParser() {
        TeacherRequestAuthority stale = TeacherRequestAuthority.begin("atomicstruct2", "snapshot-old");
        assertTrue(stale.isValid());
        String newerLease = TeacherExecutionLease.beginGlobal();
        assertFalse(stale.stillOwnsTransport());
        assertTrue(TeacherExecutionLease.isGlobalCurrent(newerLease));

        AgentAction action = TeacherProtocol.parse(
                stale.marker + "NOOP|||1.0|must-not-run", stale);

        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals("", action.executionLeaseToken);
        assertTrue(TeacherExecutionLease.isGlobalCurrent(newerLease));
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
