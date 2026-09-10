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
        int end=source.indexOf(endMarker,start);
        assertTrue(start>=0 && end>start);
        return source.substring(start,end);
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
        int lock=bind.indexOf("synchronized (DURABLE_TRANSITION_LOCK)");
        int commitRoot=bind.indexOf("AccessibilityNodeInfo commitRoot",lock);
        int commit=bind.indexOf(".putString(\"design_anchor\", a)",lock);
        assertTrue(lock>=0);
        assertTrue(commitRoot>lock);
        assertTrue(commit>commitRoot);
        assertTrue(bind.substring(lock).contains("persisted.mode == TaskState.Mode.RUNNING"));
    }

    @Test public void safeCheckpointCommitSharesAuthorityLockAndChecksDurablePostcondition() throws Exception {
        String source=repositorySource();
        String checkpoint=methodBody(source,"public synchronized boolean markSafeIfObserved(","public synchronized void pauseForHuman(");
        int lock=checkpoint.indexOf("synchronized (DURABLE_TRANSITION_LOCK)");
        int liveRoot=checkpoint.indexOf("AccessibilityNodeInfo liveRoot",lock);
        int commit=checkpoint.indexOf(".putString(LAST_SAFE_HASH, hash)",lock);
        assertTrue(lock>=0);
        assertTrue(liveRoot>lock);
        assertTrue(commit>liveRoot);
        assertTrue(checkpoint.substring(commit).contains("persisted.mode != TaskState.Mode.RUNNING"));
        assertTrue(checkpoint.substring(commit).contains("!commitSessionId.equals(currentTeacherSessionId())"));
    }
}
