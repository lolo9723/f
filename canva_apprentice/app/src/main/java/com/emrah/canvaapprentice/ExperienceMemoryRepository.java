package com.emrah.canvaapprentice;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;

public final class ExperienceMemoryRepository extends SQLiteOpenHelper {
    private static final String DB = "canva_apprentice_memory.db";
    private static final int VERSION = 4;
    private static final int MAX_ROWS = 500;
    private static final String UNBOUND_DESIGN_SCOPE = "__unbound_design__";
    private final Context appContext;

    public ExperienceMemoryRepository(Context context) {
        super(context, DB, null, VERSION);
        appContext = context.getApplicationContext();
        VerifiedCompletionMemoryHook.install(this::recordVerifiedCompletion);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        createExperiencesTable(db);
        createVerifiedCompletionsTable(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createVerifiedCompletionsTable(db);
        if (oldVersion < 3) {
            // v2 completion rows were goal-scoped only. Reusing them after adding design scope
            // could falsely teach a success from design A to design B, so discard them fail-closed.
            db.execSQL("DROP TABLE IF EXISTS verified_completions");
            createVerifiedCompletionsTable(db);
        }
        if (oldVersion < 4) {
            // v3 transition rows had no design identity. A visually similar editor state from
            // design A must never teach navigation inside design B, so legacy rows are unsafe to
            // migrate heuristically. Drop only transition memory and relearn it under exact scope.
            db.execSQL("DROP TABLE IF EXISTS experiences");
            createExperiencesTable(db);
        }
    }

    private static void createExperiencesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS experiences (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "goal_key TEXT NOT NULL," +
                "design_key TEXT NOT NULL," +
                "before_fp TEXT NOT NULL," +
                "action_type TEXT NOT NULL," +
                "target TEXT NOT NULL," +
                "after_fp TEXT NOT NULL," +
                "success_count INTEGER NOT NULL DEFAULT 0," +
                "failure_count INTEGER NOT NULL DEFAULT 0," +
                "last_at INTEGER NOT NULL," +
                "UNIQUE(goal_key,design_key,before_fp,action_type,target,after_fp))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_design_before ON experiences(design_key,before_fp)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_goal_design_before ON experiences(goal_key,design_key,before_fp)");
    }

    private static void createVerifiedCompletionsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS verified_completions (" +
                "goal_key TEXT NOT NULL," +
                "design_key TEXT NOT NULL," +
                "success_count INTEGER NOT NULL DEFAULT 0," +
                "last_at INTEGER NOT NULL," +
                "PRIMARY KEY(goal_key,design_key))");
    }

    public synchronized void record(boolean success, String goal, String beforeFp,
                                    AgentAction action, String afterFp) {
        // Learn navigation structure, not user-authored content.
        if (action == null) return;
        if (action.type != AgentAction.Type.CLICK_TEXT && action.type != AgentAction.Type.BACK) return;
        if (beforeFp == null || beforeFp.isEmpty()) return;

        LearningMemoryLeasePolicy.withCurrentLease(action, false, () -> {
            // Scope selection and persistence intentionally happen while the exact teacher
            // execution lease is held. A newer teacher request (including a BIND_DESIGN chain)
            // cannot rotate the lease between the ownership check and this live design read,
            // eliminating cross-design memory contamination from a check-then-act race.
            TaskState liveState = new TaskStateRepository(appContext).load();
            if (liveState.mode != TaskState.Mode.RUNNING) return false;
            String goalKey = goalKey(goal);
            String designKey = transitionScopeKey(liveState.designAnchor);
            String target = sanitizeTarget(action.target);
            String after = afterFp == null ? "" : afterFp;
            SQLiteDatabase db = getWritableDatabase();

            db.beginTransaction();
            try {
                db.execSQL(
                        "INSERT OR IGNORE INTO experiences(goal_key,design_key,before_fp,action_type,target,after_fp,success_count,failure_count,last_at) " +
                                "VALUES(?,?,?,?,?,?,0,0,?)",
                        new Object[]{goalKey,designKey,beforeFp,action.type.name(),target,after,System.currentTimeMillis()}
                );
                db.execSQL(
                        "UPDATE experiences SET success_count=success_count+?, failure_count=failure_count+?, last_at=? " +
                                "WHERE goal_key=? AND design_key=? AND before_fp=? AND action_type=? AND target=? AND after_fp=?",
                        new Object[]{success ? 1 : 0, success ? 0 : 1, System.currentTimeMillis(),
                                goalKey,designKey,beforeFp,action.type.name(),target,after}
                );
                db.execSQL(
                        "DELETE FROM experiences WHERE id NOT IN " +
                                "(SELECT id FROM experiences ORDER BY last_at DESC LIMIT " + MAX_ROWS + ")"
                );
                db.setTransactionSuccessful();
                return true;
            } finally {
                db.endTransaction();
            }
        });
    }

    /**
     * Persists only a final success that is already executing inside FinalDoneCommitGuard's
     * exact execution-lease and current-teacher-session boundary. The current task must still
     * be RUNNING and must have a bound design anchor; otherwise persistence fails and STOP is
     * prevented by the guard. Completion memory is additionally scoped to that exact design
     * anchor so the same goal on another Canva design cannot inherit a false success prior.
     */
    public synchronized void recordVerifiedCompletion() {
        TaskState state = new TaskStateRepository(appContext).load();
        if (state.mode != TaskState.Mode.RUNNING) {
            throw new IllegalStateException("verified completion requires RUNNING task");
        }
        if (state.goal == null || state.goal.trim().isEmpty()) {
            throw new IllegalStateException("verified completion requires task goal");
        }
        if (state.designAnchor == null || state.designAnchor.trim().isEmpty()) {
            throw new IllegalStateException("verified completion requires bound design");
        }

        String key = goalKey(state.goal);
        String designKey = completionScopeKey(state.designAnchor);
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL(
                    "INSERT OR IGNORE INTO verified_completions(goal_key,design_key,success_count,last_at) VALUES(?,?,0,?)",
                    new Object[]{key,designKey,now}
            );
            db.execSQL(
                    "UPDATE verified_completions SET success_count=success_count+1,last_at=? WHERE goal_key=? AND design_key=?",
                    new Object[]{now,key,designKey}
            );
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized String summary(String goal, String beforeFp) {
        if (beforeFp == null || beforeFp.isEmpty()) return "none";
        String goalKey = goalKey(goal);
        TaskState state = new TaskStateRepository(appContext).load();

        // DEVAM ET / process restoration invalidates continuity provenance by clearing the safe
        // checkpoint. Do not let old learned navigation influence the teacher until the current
        // Canva surface has independently become the new safe checkpoint. For a bound design,
        // SafeSnapshotPolicy can only establish that checkpoint while the exact anchor is visible.
        if (!MemoryReplayContinuityPolicy.mayRead(
                state.mode, state.lastSafeSnapshotHash, beforeFp)) {
            return "withheld: current Canva/design continuity has not been re-proven for memory replay";
        }

        String designKey = transitionScopeKey(state.designAnchor);
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT goal_key,action_type,target,after_fp,success_count,failure_count " +
                        "FROM experiences WHERE design_key=? AND before_fp=? " +
                        "ORDER BY CASE WHEN goal_key=? THEN 0 ELSE 1 END, " +
                        "(success_count-failure_count) DESC, last_at DESC LIMIT 5",
                new String[]{designKey,beforeFp,goalKey}
        );
        StringBuilder out = new StringBuilder();
        try {
            while (c.moveToNext()) {
                boolean exact = goalKey.equals(c.getString(0));
                String type = c.getString(1);
                String target = c.getString(2);
                String after = c.getString(3);
                int successes = c.getInt(4);
                int failures = c.getInt(5);
                double trust = (successes + 1.0) / (successes + failures + 2.0);
                out.append("exactGoal=").append(exact)
                        .append(" exactDesign=true")
                        .append(" action=").append(type)
                        .append(" target=").append(target)
                        .append(" successes=").append(successes)
                        .append(" failures=").append(failures)
                        .append(" trust=").append(String.format(Locale.US,"%.2f",trust))
                        .append(" expectedAfter=").append(shortFp(after))
                        .append('\n');
            }
        } finally {
            c.close();
        }

        if (state.designAnchor != null && !state.designAnchor.trim().isEmpty()) {
            int verifiedCompletions = verifiedCompletionCount(
                    goalKey, completionScopeKey(state.designAnchor));
            if (verifiedCompletions > 0) {
                out.append("verifiedDesignGoalCompletions=").append(verifiedCompletions)
                        .append(" (final visual QA + exact bound-design proof)\n");
            }
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private int verifiedCompletionCount(String goalKey, String designKey) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT success_count FROM verified_completions WHERE goal_key=? AND design_key=?",
                new String[]{goalKey,designKey}
        );
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    public synchronized int learnedTransitionCount() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM experiences WHERE success_count>0", null
        );
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    static String transitionScopeKey(String designAnchor) {
        String normalized = normalize(designAnchor);
        return normalized.isEmpty() ? UNBOUND_DESIGN_SCOPE : sha256(normalized);
    }

    static String completionScopeKey(String designAnchor) {
        String normalized = normalize(designAnchor);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("completion scope requires bound design");
        }
        return sha256(normalized);
    }

    private static String goalKey(String goal) {
        String n = normalize(goal);
        return sha256(n);
    }

    private static String sanitizeTarget(String target) {
        String t = target == null ? "" : target.trim().replace('\n',' ');
        if (t.length() > 120) t = t.substring(0,120);
        return t;
    }

    private static String shortFp(String fp) {
        if (fp == null) return "";
        return fp.length() <= 12 ? fp : fp.substring(0,12);
    }

    private static String normalize(String s) {
        String x = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
        return x.replace('ı','i').replaceAll("\\s+"," ").trim();
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.US,"%02x",x));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
