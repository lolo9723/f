package com.emrah.canvaapprentice;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

public final class TaskStateRepository {
    private static final String PREFS = "agent_state_v2";
    private static final String SESSION_ID = "teacher_session_id";
    private final SharedPreferences prefs;

    public TaskStateRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized TaskState load() {
        String modeRaw = prefs.getString("mode", TaskState.Mode.IDLE.name());
        TaskState.Mode mode;
        try { mode = TaskState.Mode.valueOf(modeRaw); }
        catch (Exception ignored) { mode = TaskState.Mode.IDLE; }

        return new TaskState(
                prefs.getString("goal", ""),
                prefs.getString("design_fingerprint", ""),
                prefs.getString("design_anchor", ""),
                prefs.getString("last_safe_hash", ""),
                prefs.getString("human_reason", ""),
                mode,
                prefs.getBoolean("allow_new_design", false),
                prefs.getInt("step", 0)
        );
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
                .putString("last_safe_hash", "")
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
        prefs.edit().putString("design_anchor", a).apply();
    }

    public synchronized void markSafe(String hash) {
        TaskState state = load();
        prefs.edit()
                .putString("last_safe_hash", hash == null ? "" : hash)
                .putInt("step", state.step + 1)
                .apply();
    }

    public synchronized void pauseForHuman(String reason) {
        prefs.edit()
                .putString("mode", TaskState.Mode.HUMAN_TAKEOVER.name())
                .putString("human_reason", reason == null ? "" : reason)
                .putString(SESSION_ID, newSessionId())
                .apply();
    }

    public synchronized void resume() {
        prefs.edit()
                .putString("mode", TaskState.Mode.RUNNING.name())
                .putString("human_reason", "")
                .putString(SESSION_ID, newSessionId())
                .apply();
    }

    public synchronized void stop() {
        prefs.edit()
                .putString("mode", TaskState.Mode.STOPPED.name())
                .putString("human_reason", "")
                .putString(SESSION_ID, newSessionId())
                .apply();
    }

    private static String newSessionId() {
        return UUID.randomUUID().toString();
    }
}
