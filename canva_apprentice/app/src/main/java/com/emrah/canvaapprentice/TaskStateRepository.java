package com.emrah.canvaapprentice;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.UUID;
import java.util.function.Supplier;

public final class TaskStateRepository {
    private static final String PREFS = "agent_state_v2";
    private static final String SESSION_ID = "teacher_session_id";
    private static final String LAST_SAFE_HASH = "last_safe_hash";
    private static final String LAST_SAFE_ANCHOR = "last_safe_anchor";
    // SharedPreferences is process-wide but synchronized instance methods are not. All writes that
    // rotate execution/session authority must therefore share one process-wide lock, otherwise two
    // repository instances can both validate an old state and then race their commits.
    private static final Object DURABLE_TRANSITION_LOCK = new Object();
    private static boolean processContinuityInitialized = false;
    private final SharedPreferences prefs;

    public TaskStateRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static <T> T withDurableAuthorityLock(Supplier<T> operation) {
        if (operation == null) throw new IllegalArgumentException("durable authority operation required");
        synchronized (DURABLE_TRANSITION_LOCK) {
            return operation.get();
        }
    }

    public synchronized TaskState load() {
        invalidatePersistedRuntimeContinuityOnFirstLoad();
        String modeRaw = prefs.getString("mode", TaskState.Mode.IDLE.name());
        TaskState.Mode mode;
        try { mode = TaskState.Mode.valueOf(modeRaw); }
        catch (Exception ignored) { mode = TaskState.Mode.IDLE; }
        String designAnchor = prefs.getString("design_anchor", "");
        String persistedSafeHash = prefs.getString(LAST_SAFE_HASH, "");
        String persistedSafeAnchor = prefs.getString(LAST_SAFE_ANCHOR, "");
        String trustedSafeHash = SafeSnapshotPolicy.mayRestoreCheckpoint(
                mode,designAnchor,persistedSafeAnchor,persistedSafeHash)
                ? persistedSafeHash : "";
        return new TaskState(
                prefs.getString("goal", ""),
                prefs.getString("design_fingerprint", ""),
                designAnchor,
                trustedSafeHash,
                prefs.getString("human_reason", ""),
                mode,
                prefs.getBoolean("allow_new_design", false),
                prefs.getInt("step", 0)
        );
    }

    private void invalidatePersistedRuntimeContinuityOnFirstLoad() {
        synchronized (DURABLE_TRANSITION_LOCK) {
            if (processContinuityInitialized) return;
            String modeRaw = prefs.getString("mode", TaskState.Mode.IDLE.name());
            TaskState.Mode mode;
            try { mode = TaskState.Mode.valueOf(modeRaw); }
            catch (Exception ignored) { mode = TaskState.Mode.IDLE; }
            if (!RuntimeRestoreContinuityPolicy.mustInvalidate(mode)) {
                processContinuityInitialized = true;
                return;
            }
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(LAST_SAFE_HASH, "")
                    .putString(LAST_SAFE_ANCHOR, "")
                    .putString(SESSION_ID, newSessionId());
            if (RuntimeRestoreContinuityPolicy.mustRequireHumanResume(mode)) {
                editor.putString("mode", TaskState.Mode.HUMAN_TAKEOVER.name())
                        .putString("human_reason",
                                "Ajan işlemi yeniden başladı. Eski çalışma bağlamı güvenlik nedeniyle geçersiz sayıldı; " +
                                "Canva'daki mevcut tasarımı kontrol edip DEVAM ET'e bas.");
            }
            boolean committed = editor.commit();
            if (!committed) {
                throw new IllegalStateException("Durable runtime continuity invalidation failed");
            }
            processContinuityInitialized = true;
        }
    }

    public synchronized String currentTeacherSessionId() {
        synchronized (DURABLE_TRANSITION_LOCK) {
            String id = prefs.getString(SESSION_ID, "");
            if (id == null || id.isEmpty()) {
                id = newSessionId();
                requireDurableCommit(
                        prefs.edit().putString(SESSION_ID, id),
                        "teacher session creation");
                String persisted = prefs.getString(SESSION_ID, "");
                if (!id.equals(persisted)) {
                    throw new IllegalStateException("Durable teacher session creation postcondition failed");
                }
            }
            return id;
        }
    }

    public synchronized void start(String goal, boolean allowNewDesign, String currentFingerprint) {
        synchronized (DURABLE_TRANSITION_LOCK) {
            String expectedSessionId = newSessionId();
            requireDurableCommit(
                    prefs.edit()
                            .putString("goal", goal == null ? "" : goal.trim())
                            .putBoolean("allow_new_design", allowNewDesign)
                            .putString("design_fingerprint", currentFingerprint == null ? "" : currentFingerprint)
                            .putString("design_anchor", "")
                            .putString(LAST_SAFE_HASH, "")
                            .putString(LAST_SAFE_ANCHOR, "")
                            .putString("human_reason", "")
                            .putString("mode", TaskState.Mode.RUNNING.name())
                            .putString(SESSION_ID, expectedSessionId)
                            .putInt("step", 0),
                    "task start");
            requireDurableTransitionPostcondition(TaskState.Mode.RUNNING, expectedSessionId, "task start");
        }
    }

    public synchronized void bindDesignAnchor(String anchor) {
        bindDesignAnchor(anchor, currentTeacherSessionId());
    }

    public synchronized boolean bindDesignAnchor(String anchor, String actionTeacherSessionId) {
        if (anchor == null) return false;
        String a = anchor.trim();
        if (a.isEmpty()) return false;
        TaskState current = load();
        if (current.mode != TaskState.Mode.RUNNING) return false;
        if (!DesignAnchorPersistencePolicy.preservesBoundIdentity(current.designAnchor, a)) return false;
        final String observedTeacherSessionId = currentTeacherSessionId();
        if (!DesignAnchorPersistencePolicy.mayCommit(
                current.mode, actionTeacherSessionId, observedTeacherSessionId,
                observedTeacherSessionId, a)) return false;

        AgentAccessibilityService service = AgentAccessibilityService.INSTANCE;
        if (service == null) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        String pkg = root != null && root.getPackageName() != null
                ? root.getPackageName().toString() : "";
        if (!AgentConstants.CANVA_PACKAGE.equals(pkg)) return false;
        UiTreeSnapshot live = UiTreeSnapshot.capture(root);
        boolean exactAnchorVisible = live.containsText(a);
        boolean homeVisible = live.looksLikeCanvaHome();
        if (!DesignAnchorPolicy.mayBindVisibleEditor(a, exactAnchorVisible, homeVisible)) return false;

        synchronized (DURABLE_TRANSITION_LOCK) {
            if (!RuntimeOwnerPolicy.isCurrent(service, AgentAccessibilityService.INSTANCE)) return false;
            TaskState rechecked = load();
            String currentTeacherSessionId = currentTeacherSessionId();
            if (!DesignAnchorPersistencePolicy.preservesBoundIdentity(rechecked.designAnchor, a)) return false;
            if (!DesignAnchorPersistencePolicy.mayCommit(
                    rechecked.mode, actionTeacherSessionId, observedTeacherSessionId,
                    currentTeacherSessionId, a)) return false;

            AccessibilityNodeInfo commitRoot = service.getRootInActiveWindow();
            String commitPkg = commitRoot != null && commitRoot.getPackageName() != null
                    ? commitRoot.getPackageName().toString() : "";
            if (!AgentConstants.CANVA_PACKAGE.equals(commitPkg)) return false;
            UiTreeSnapshot commitLive = UiTreeSnapshot.capture(commitRoot);
            if (!DesignAnchorPolicy.mayBindVisibleEditor(
                    a, commitLive.containsText(a), commitLive.looksLikeCanvaHome())) return false;

            // Re-binding the exact same durable identity is idempotent. Do not clear a valid
            // checkpoint or invalidate unrelated requests merely because the teacher repeated BIND.
            if (a.equals(rechecked.designAnchor)) return true;

            boolean committed = prefs.edit()
                    .putString("design_anchor", a)
                    .putString(LAST_SAFE_HASH, "")
                    .putString(LAST_SAFE_ANCHOR, "")
                    .commit();
            if (!committed) return false;
            TaskState persisted = load();
            boolean postcondition = persisted.mode == TaskState.Mode.RUNNING
                    && a.equals(persisted.designAnchor)
                    && currentTeacherSessionId.equals(currentTeacherSessionId());
            if (!postcondition) return false;

            // A newly bound design changes the authority context of every structural/memory-backed
            // teacher request issued while the design was unbound. Poison those in-flight replies
            // only after the durable anchor postcondition succeeds, so stale advice cannot execute
            // against the newly established exact-design identity.
            CheckpointRequestGuard.onCheckpointCommitted();
            return true;
        }
    }

    @Deprecated
    public void markSafe(String hash) {
        final String expectedHash = hash == null ? "" : hash.trim();
        if (expectedHash.isEmpty()) return;
        final TaskState state;
        final String expectedAnchor;
        final String expectedSession;
        synchronized (this) {
            state = load();
            if (state.mode != TaskState.Mode.RUNNING || state.designAnchor.isEmpty()) return;
            expectedAnchor = state.designAnchor.trim();
            expectedSession = currentTeacherSessionId();
        }

        final AgentAccessibilityService service = AgentAccessibilityService.INSTANCE;
        if (service == null) return;
        AccessibilityNodeInfo beforeRoot = service.getRootInActiveWindow();
        String beforePkg = beforeRoot != null && beforeRoot.getPackageName() != null
                ? beforeRoot.getPackageName().toString() : "";
        if (!AgentConstants.CANVA_PACKAGE.equals(beforePkg)) return;
        UiTreeSnapshot before = UiTreeSnapshot.capture(beforeRoot);
        if (!expectedHash.equals(before.stableFingerprint())) return;
        if (!before.containsText(expectedAnchor) || before.looksLikeCanvaHome()) return;

        service.captureScreenshotForDiagnostics(file -> {
            if (file == null) return;
            if (!RuntimeOwnerPolicy.isCurrent(service, AgentAccessibilityService.INSTANCE)) return;
            AccessibilityNodeInfo recapturedRoot = service.getRootInActiveWindow();
            String recapturedPkg = recapturedRoot != null && recapturedRoot.getPackageName() != null
                    ? recapturedRoot.getPackageName().toString() : "";
            if (!AgentConstants.CANVA_PACKAGE.equals(recapturedPkg)) return;
            UiTreeSnapshot recaptured = UiTreeSnapshot.capture(recapturedRoot);
            String visualFingerprint = VisualFingerprint.fromFile(file);
            if (!RuntimeOwnerPolicy.isCurrent(service, AgentAccessibilityService.INSTANCE)) return;
            markSafeIfObserved(
                    expectedAnchor,
                    expectedSession,
                    expectedHash,
                    recaptured.stableFingerprint(),
                    recaptured.containsText(expectedAnchor),
                    recaptured.looksLikeCanvaHome(),
                    visualFingerprint
            );
        });
    }

    public synchronized boolean markSafeIfObserved(String expectedBoundAnchor,
                                                   String expectedTeacherSessionId,
                                                   String structuralFingerprint,
                                                   String recapturedFingerprint,
                                                   boolean recapturedAnchorVisible,
                                                   boolean recapturedCanvaHomeVisible,
                                                   String visualFingerprint) {
        TaskState state = load();
        String currentSessionId = currentTeacherSessionId();
        if (!SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                state.mode,
                state.designAnchor,
                expectedBoundAnchor,
                currentSessionId,
                expectedTeacherSessionId,
                structuralFingerprint,
                recapturedFingerprint,
                recapturedAnchorVisible,
                recapturedCanvaHomeVisible,
                visualFingerprint)) {
            return false;
        }

        synchronized (DURABLE_TRANSITION_LOCK) {
            AgentAccessibilityService service = AgentAccessibilityService.INSTANCE;
            if (service == null || !RuntimeOwnerPolicy.isCurrent(service, AgentAccessibilityService.INSTANCE)) return false;
            AccessibilityNodeInfo liveRoot = service.getRootInActiveWindow();
            String livePkg = liveRoot != null && liveRoot.getPackageName() != null
                    ? liveRoot.getPackageName().toString() : "";
            if (!AgentConstants.CANVA_PACKAGE.equals(livePkg)) return false;
            UiTreeSnapshot live = UiTreeSnapshot.capture(liveRoot);
            if (!SafeSnapshotPolicy.commitBoundaryStillMatches(
                    recapturedFingerprint,
                    live.stableFingerprint(),
                    live.containsText(expectedBoundAnchor),
                    live.looksLikeCanvaHome())) {
                return false;
            }

            TaskState commitState = load();
            String commitSessionId = currentTeacherSessionId();
            if (!SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                    commitState.mode,
                    commitState.designAnchor,
                    expectedBoundAnchor,
                    commitSessionId,
                    expectedTeacherSessionId,
                    structuralFingerprint,
                    recapturedFingerprint,
                    recapturedAnchorVisible,
                    recapturedCanvaHomeVisible,
                    visualFingerprint)) {
                return false;
            }

            String owner = commitState.designAnchor.trim();
            String hash = recapturedFingerprint.trim();
            boolean committed = prefs.edit()
                    .putString(LAST_SAFE_HASH, hash)
                    .putString(LAST_SAFE_ANCHOR, owner)
                    .putInt("step", commitState.step + 1)
                    .commit();
            if (!committed) return false;
            TaskState persisted = load();
            if (persisted.mode != TaskState.Mode.RUNNING
                    || !owner.equals(persisted.designAnchor)
                    || !hash.equals(persisted.lastSafeSnapshotHash)
                    || !commitSessionId.equals(currentTeacherSessionId())) {
                return false;
            }
            CheckpointRequestGuard.onCheckpointCommitted();
            return true;
        }
    }

    public synchronized void pauseForHuman(String reason) {
        synchronized (DURABLE_TRANSITION_LOCK) {
            TaskState current = load();
            if (!HumanTakeoverTransitionPolicy.mayPause(current.mode)) {
                throw new IllegalStateException("Human takeover rejected outside RUNNING");
            }
            String expectedSessionId = newSessionId();
            requireDurableCommit(
                    prefs.edit()
                            .putString("mode", TaskState.Mode.HUMAN_TAKEOVER.name())
                            .putString("human_reason", reason == null ? "" : reason)
                            .putString(LAST_SAFE_HASH, "")
                            .putString(LAST_SAFE_ANCHOR, "")
                            .putString(SESSION_ID, expectedSessionId),
                    "human takeover");
            requireDurableTransitionPostcondition(
                    TaskState.Mode.HUMAN_TAKEOVER, expectedSessionId, "human takeover");
        }
    }

    public synchronized void resume() {
        synchronized (DURABLE_TRANSITION_LOCK) {
            TaskState current = load();
            if (!ResumeTransitionPolicy.mayResume(current.mode)) {
                throw new IllegalStateException("Resume rejected outside HUMAN_TAKEOVER");
            }
            String expectedSessionId = newSessionId();
            requireDurableCommit(
                    prefs.edit()
                            .putString("mode", TaskState.Mode.RUNNING.name())
                            .putString("human_reason", "")
                            .putString(LAST_SAFE_HASH, "")
                            .putString(LAST_SAFE_ANCHOR, "")
                            .putString(SESSION_ID, expectedSessionId),
                    "task resume");
            requireDurableTransitionPostcondition(TaskState.Mode.RUNNING, expectedSessionId, "task resume");
        }
    }

    public synchronized void stop() {
        synchronized (DURABLE_TRANSITION_LOCK) {
            String expectedSessionId = newSessionId();
            requireDurableCommit(
                    prefs.edit()
                            .putString("mode", TaskState.Mode.STOPPED.name())
                            .putString("human_reason", "")
                            .putString(LAST_SAFE_HASH, "")
                            .putString(LAST_SAFE_ANCHOR, "")
                            .putString(SESSION_ID, expectedSessionId),
                    "STOP");
            requireDurableTransitionPostcondition(TaskState.Mode.STOPPED, expectedSessionId, "STOP");
        }
    }

    private void requireDurableTransitionPostcondition(TaskState.Mode expectedMode,
                                                       String expectedSessionId,
                                                       String transition) {
        String persistedMode = prefs.getString("mode", "");
        String persistedSessionId = prefs.getString(SESSION_ID, "");
        if (!expectedMode.name().equals(persistedMode)
                || !expectedSessionId.equals(persistedSessionId)) {
            throw new IllegalStateException("Durable " + transition + " postcondition failed");
        }
    }

    private static void requireDurableCommit(SharedPreferences.Editor editor, String transition) {
        if (!editor.commit()) {
            throw new IllegalStateException("Durable " + transition + " persistence failed");
        }
    }

    private static String newSessionId() {
        return UUID.randomUUID().toString();
    }
}
