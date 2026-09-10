package com.emrah.canvaapprentice;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public final class LearningMemoryAuthorityBoundaryTest {
    private static String source(String file) throws Exception {
        Path cursor = Paths.get("").toAbsolutePath().normalize();
        while (cursor != null) {
            Path direct = cursor.resolve("app/src/main/java/com/emrah/canvaapprentice/" + file);
            if (Files.isRegularFile(direct)) return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
            Path nested = cursor.resolve("canva_apprentice/app/src/main/java/com/emrah/canvaapprentice/" + file);
            if (Files.isRegularFile(nested)) return new String(Files.readAllBytes(nested), StandardCharsets.UTF_8);
            cursor = cursor.getParent();
        }
        throw new AssertionError("Missing source: " + file);
    }

    private static String methodBody(String source, String start, String end) {
        int a = source.indexOf(start);
        int b = a < 0 ? -1 : source.indexOf(end, a);
        assertTrue("missing method start: " + start, a >= 0);
        assertTrue("missing method end: " + end, b > a);
        return source.substring(a, b);
    }

    @Test public void transitionMemoryCommitUsesDurableTaskAuthorityBoundary() throws Exception {
        String source = source("ExperienceMemoryRepository.java");
        String body = methodBody(source, "public synchronized void record(", "public synchronized void recordVerifiedCompletion()");
        int lease = body.indexOf("LearningMemoryLeasePolicy.withCurrentLease");
        int authority = body.indexOf("TaskStateRepository.withDurableAuthorityLock");
        int state = body.indexOf("TaskState liveState");
        int transaction = body.indexOf("db.beginTransaction()");
        assertTrue("execution lease must remain the outer provenance boundary", lease >= 0);
        assertTrue("durable authority boundary must be acquired after execution provenance", authority > lease);
        assertTrue("RUNNING/design/checkpoint state must be re-read inside durable authority boundary", state > authority);
        assertTrue("memory DB mutation must occur only after locked live-state validation", transaction > state);
    }

    @Test public void verifiedCompletionCommitUsesSameDurableAuthorityBoundary() throws Exception {
        String source = source("ExperienceMemoryRepository.java");
        String body = methodBody(source, "public synchronized void recordVerifiedCompletion()", "public synchronized String summary(");
        int authority = body.indexOf("TaskStateRepository.withDurableAuthorityLock");
        int state = body.indexOf("TaskState state");
        int transaction = body.indexOf("db.beginTransaction()");
        assertTrue("verified completion must share STOP/resume/session authority boundary", authority >= 0);
        assertTrue("verified completion must re-read task state inside boundary", state > authority);
        assertTrue("verified completion DB write must remain inside boundary", transaction > state);
    }

    @Test public void authorityHelperSynchronizesOnTransitionLock() throws Exception {
        String source = source("TaskStateRepository.java");
        String body = methodBody(source, "static <T> T withDurableAuthorityLock", "public synchronized TaskState load()");
        assertTrue(body.contains("synchronized (DURABLE_TRANSITION_LOCK)"));
        assertTrue(body.contains("return operation.get()"));
    }
}
