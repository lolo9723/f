package com.emrah.canvaapprentice;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public final class TaskStateRepositoryProcessLockInvariantTest {
    private static String repositorySource() throws Exception {
        Path cursor=Paths.get("").toAbsolutePath().normalize();
        Path path=null;
        while(cursor!=null){
            Path direct=cursor.resolve("app/src/main/java/com/emrah/canvaapprentice/TaskStateRepository.java");
            if(Files.isRegularFile(direct)){
                path=direct;
                break;
            }
            Path nested=cursor.resolve("canva_apprentice/app/src/main/java/com/emrah/canvaapprentice/TaskStateRepository.java");
            if(Files.isRegularFile(nested)){
                path=nested;
                break;
            }
            cursor=cursor.getParent();
        }
        assertTrue("TaskStateRepository source must be available to the safety test",path!=null && Files.isRegularFile(path));
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }

    private static String methodBody(String source,String startMarker,String endMarker){
        int start=source.indexOf(startMarker);
        int end=start<0 ? -1 : source.indexOf(endMarker,start);
        assertTrue("Missing method start marker: "+startMarker,start>=0);
        assertTrue("Missing method end marker after: "+startMarker,end>start);
        return source.substring(start,end);
    }

    private static void assertOrdered(String body,String first,String second,String message){
        int firstIndex=body.indexOf(first);
        int secondIndex=body.indexOf(second);
        assertTrue(message+" (missing first token)",firstIndex>=0);
        assertTrue(message+" (missing second token)",secondIndex>=0);
        assertTrue(message+" (wrong order)",secondIndex>firstIndex);
    }

    @Test public void authorityRotatingTransitionsShareProcessWideLock() throws Exception {
        String source=repositorySource();
        assertTrue(source.contains("private static final Object DURABLE_TRANSITION_LOCK"));
        assertTrue(methodBody(source,"public synchronized void start(","public synchronized void bindDesignAnchor")
                .contains("synchronized (DURABLE_TRANSITION_LOCK)"));
        assertTrue(methodBody(source,"public synchronized void pauseForHuman(","public synchronized void resume()")
                .contains("synchronized (DURABLE_TRANSITION_LOCK)"));
        assertTrue(methodBody(source,"public synchronized void resume()","public synchronized void stop()")
                .contains("synchronized (DURABLE_TRANSITION_LOCK)"));
        assertTrue(methodBody(source,"public synchronized void stop()","private void requireDurableTransitionPostcondition")
                .contains("synchronized (DURABLE_TRANSITION_LOCK)"));
    }

    @Test public void sessionCreationAndRestoreInvalidationUseSameLock() throws Exception {
        String source=repositorySource();
        assertTrue(methodBody(source,"private void invalidatePersistedRuntimeContinuityOnFirstLoad()","public synchronized String currentTeacherSessionId()")
                .contains("synchronized (DURABLE_TRANSITION_LOCK)"));
        String session=methodBody(source,"public synchronized String currentTeacherSessionId()","public synchronized void start(");
        assertTrue(session.contains("synchronized (DURABLE_TRANSITION_LOCK)"));
        assertTrue(session.contains("Durable teacher session creation postcondition failed"));
    }

    @Test public void designAnchorCommitSharesAuthorityLockAndReobservesUiInsideIt() throws Exception {
        String source=repositorySource();
        String bind=methodBody(source,"public synchronized boolean bindDesignAnchor(","@Deprecated");
        assertOrdered(bind,
                "synchronized (DURABLE_TRANSITION_LOCK)",
                "AccessibilityNodeInfo commitRoot",
                "design anchor must re-observe the live Canva editor inside the authority lock");
        assertOrdered(bind,
                "AccessibilityNodeInfo commitRoot",
                ".putString(\"design_anchor\", a)",
                "design anchor persistence must happen only after the locked UI re-observation");
        assertOrdered(bind,
                ".putString(\"design_anchor\", a)",
                "persisted.mode == TaskState.Mode.RUNNING",
                "design anchor commit must verify durable RUNNING postcondition");
        assertTrue("design anchor commit must verify session authority after persistence",
                bind.contains("currentTeacherSessionId.equals(currentTeacherSessionId())"));
    }

    @Test public void safeCheckpointCommitSharesAuthorityLockAndChecksDurablePostcondition() throws Exception {
        String source=repositorySource();
        String checkpoint=methodBody(source,"public synchronized boolean markSafeIfObserved(","public synchronized void pauseForHuman(");
        assertOrdered(checkpoint,
                "synchronized (DURABLE_TRANSITION_LOCK)",
                "AccessibilityNodeInfo liveRoot",
                "safe checkpoint must re-observe the live Canva editor inside the authority lock");
        assertOrdered(checkpoint,
                "AccessibilityNodeInfo liveRoot",
                ".putString(LAST_SAFE_HASH, hash)",
                "safe checkpoint persistence must happen only after the locked UI re-observation");
        assertOrdered(checkpoint,
                ".putString(LAST_SAFE_HASH, hash)",
                "persisted.mode != TaskState.Mode.RUNNING",
                "safe checkpoint commit must verify durable RUNNING postcondition");
        assertTrue("safe checkpoint commit must verify session authority after persistence",
                checkpoint.contains("!commitSessionId.equals(currentTeacherSessionId())"));
    }
}
