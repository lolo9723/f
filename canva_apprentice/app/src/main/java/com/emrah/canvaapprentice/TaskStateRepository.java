package com.emrah.canvaapprentice;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.UUID;

public final class TaskStateRepository {
    private static final String PREFS = "agent_state_v2";
    private static final String SESSION_ID = "teacher_session_id";
    private static final String LAST_SAFE_HASH = "last_safe_hash";
    private static final String LAST_SAFE_ANCHOR = "last_safe_anchor";
    private static boolean processContinuityInitialized = false;
    private final SharedPreferences prefs;

    public TaskStateRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
        if (processContinuityInitialized) return;
        processContinuityInitialized = true;

        String modeRaw = prefs.getString("mode", TaskState.Mode.IDLE.name());
        TaskState.Mode mode;
        try { mode = TaskState.Mode.valueOf(modeRaw); }
        catch (Exception ignored) { mode = TaskState.Mode.IDLE; }

        if (!RuntimeRestoreContinuityPolicy.mustInvalidate(mode)) return;
        prefs.edit()
                .putString(LAST_SAFE_HASH, "")
                .putString(LAST_SAFE_ANCHOR, "")
                .putString(SESSION_ID, newSessionId())
                .apply();
    }

    public synchronized String currentTeacherSessionId() {
        String id = prefs.getString(SESSION_ID, "");
        if (id == null || id.isEmpty()) {
            id = newSessionId();
            prefs.edit().putString(SESSION_ID, id).apply();
        }
        return id;
    }

    public synchronized void start(String goal, boolean allowNewDesign, String currentFingerprint) {
        prefs.edit()
                .putString("goal", goal == null ? "" : goal.trim())
                .putBoolean("allow_new_design", allowNewDesign)
                .putString("design_fingerprint", currentFingerprint == null ? "" : currentFingerprint)
                .putString("design_anchor", "")
                .putString(LAST_SAFE_HASH, "")
                .putString(LAST_SAFE_ANCHOR, "")
                .putString("human_reason", "")
                .putString("mode", TaskState.Mode.RUNNING.name())
                .putString(SESSION_ID, newSessionId())
                .putInt("step", 0)
                .apply();
    }

    public synchronized void bindDesignAnchor(String anchor) {
        if (anchor == null) return;
        String a = anchor.trim();
        if (a.isEmpty()) return;
        prefs.edit()
                .putString("design_anchor", a)
                .putString(LAST_SAFE_HASH, "")
                .putString(LAST_SAFE_ANCHOR, "")
                .apply();
    }

    /**
     * Compatibility entry point used by the production Canva cycle.
     *
     * This method no longer persists structural-only evidence. Instead it starts a screenshot-backed
     * admission attempt tied to the exact current teacher session and bound design. The structural
     * fingerprint supplied by the cycle must still match the live Canva tree before capture, and the
     * tree is recaptured after the screenshot. Only markSafeIfObserved(...) is allowed to persist.
     */
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

            AccessibilityNodeInfo recapturedRoot = service.getRootInActiveWindow();
            String recapturedPkg = recapturedRoot != null && recapturedRoot.getPackageName() != null
                    ? recapturedRoot.getPackageName().toString() : "";
            if (!AgentConstants.CANVA_PACKAGE.equals(recapturedPkg)) return;

            UiTreeSnapshot recaptured = UiTreeSnapshot.capture(recapturedRoot);
            String visualFingerprint = VisualFingerprint.fromFile(file);
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

        String owner = state.designAnchor.trim();
        String hash = recapturedFingerprint.trim();
        prefs.edit()
                .putString(LAST_SAFE_HASH, hash)
                .putString(LAST_SAFE_ANCHOR, owner)
                .putInt("step", state.step + 1)
                .apply();
        return true;
    }

    public synchronized void pauseForHuman(String reason) {
        prefs.edit()
                .putString("mode", TaskState.Mode.HUMAN_TAKEOVER.name())
                .putString("human_reason", reason == null ? "" : reason)
                .putString(LAST_SAFE_HASH, "")
                .putString(LAST_SAFE_ANCHOR, "")
                .putString(SESSION_ID, newSessionId())
                .apply();
    }

    public synchronized void resume() {
        prefs.edit()
                .putString("mode", TaskState.Mode.RUNNING.name())
                .putString("human_reason", "")
                .putString(LAST_SAFE_HASH, "")
                .putString(LAST_SAFE_ANCHOR, "")
                .putString(SESSION_ID, newSessionId())
                .apply();
    }

    public synchronized void stop() {
        prefs.edit()
                .putString("mode", TaskState.Mode.STOPPED.name())
                .putString("human_reason", "")
                .putString(LAST_SAFE_HASH, "")
                .putString(LAST_SAFE_ANCHOR, "")
                .putString(SESSION_ID, newSessionId())
                .apply();
    }

    private static String newSessionId() {
        return UUID.randomUUID().toString();
    }
}
