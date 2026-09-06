package com.emrah.canvaapprentice;

import android.content.Context;
import android.content.SharedPreferences;
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
        // last_safe_hash and the teacher session are runtime provenance, not durable proof. Android
        // may recreate the process/service while Canva has moved, so a checkpoint from the previous
        // process must never authorize design continuity or learned-memory replay in the new one.
        // The static guard makes this a once-per-process invalidation even if another component
        // constructs the repository before the accessibility service.
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
        // Any checkpoint learned before binding had no proof that it belonged to this design.
        // Clear both the hash and its owner atomically with the bind so pre-bind/home/unknown or
        // previously-bound fingerprints can never become post-bind continuity evidence.
        prefs.edit()
                .putString("design_anchor", a)
                .putString(LAST_SAFE_HASH, "")
                .putString(LAST_SAFE_ANCHOR, "")
                .apply();
    }

    public synchronized void markSafe(String hash) {
        TaskState state = load();
        // Persistence is a security boundary, not a blind setter. A stale callback or future
        // call-site bug must never be able to recreate continuity while paused/stopped, before an
        // exact design is bound, or with an unusable fingerprint.
        if (!SafeSnapshotPolicy.mayPersistCheckpoint(state.mode, state.designAnchor, hash)) return;
        String owner = state.designAnchor.trim();
        prefs.edit()
                .putString(LAST_SAFE_HASH, hash.trim())
                .putString(LAST_SAFE_ANCHOR, owner)
                .putInt("step", state.step + 1)
                .apply();
    }

    /**
     * Atomically validates and persists a screenshot-backed continuity checkpoint.
     *
     * The caller supplies the exact design/session/structural observation that initiated the
     * screenshot plus the recaptured structural facts and visual fingerprint. All current runtime
     * state is re-read while holding this repository monitor, and the safe hash is written before
     * releasing it. This closes the policy-check -> markSafe TOCTOU window where DEVAM ET, a design
     * rebind, HUMAN_TAKEOVER or STOP could otherwise change authority between validation and write.
     */
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
        // HUMAN_TAKEOVER revokes all runtime continuity authority immediately. Clearing the durable
        // pair as well as rotating the teacher session prevents any reader from observing a stale
        // checkpoint while the user is authenticating, dismissing dialogs, or navigating Canva.
        prefs.edit()
                .putString("mode", TaskState.Mode.HUMAN_TAKEOVER.name())
                .putString("human_reason", reason == null ? "" : reason)
                .putString(LAST_SAFE_HASH, "")
                .putString(LAST_SAFE_ANCHOR, "")
                .putString(SESSION_ID, newSessionId())
                .apply();
    }

    public synchronized void resume() {
        // Human takeover can legitimately navigate, dismiss dialogs, authenticate, or even
        // momentarily leave/re-enter the bound design. A checkpoint captured before takeover is
        // therefore stale provenance and must never authorize post-resume design continuity.
        // Preserve the bound design anchor, but force the runtime to establish a fresh safe
        // checkpoint from the current Canva editor before relying on snapshot continuity again.
        prefs.edit()
                .putString("mode", TaskState.Mode.RUNNING.name())
                .putString("human_reason", "")
                .putString(LAST_SAFE_HASH, "")
                .putString(LAST_SAFE_ANCHOR, "")
                .putString(SESSION_ID, newSessionId())
                .apply();
    }

    public synchronized void stop() {
        // STOPPED is a hard revocation boundary. Do not leave a design-scoped runtime checkpoint
        // resident for future readers/processes; a later task must prove continuity from scratch.
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
