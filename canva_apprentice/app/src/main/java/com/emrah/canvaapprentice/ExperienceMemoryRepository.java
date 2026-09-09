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
    private static final int VERSION = 5;
    private static final int MAX_ROWS = 500;
    private static final int MIN_VERIFIED_SUCCESSES_FOR_REPLAY = 2;
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
            db.execSQL("DROP TABLE IF EXISTS verified_completions");
            createVerifiedCompletionsTable(db);
        }
        if (oldVersion < 4) {
            db.execSQL("DROP TABLE IF EXISTS experiences");
            createExperiencesTable(db);
        }
        if (oldVersion < 5) {
            db.execSQL(
                    "UPDATE experiences SET failure_count = MAX(failure_count, COALESCE((" +
                            "SELECT f.failure_count FROM experiences f " +
                            "WHERE f.goal_key=experiences.goal_key " +
                            "AND f.design_key=experiences.design_key " +
                            "AND f.before_fp=experiences.before_fp " +
                            "AND f.action_type=experiences.action_type " +
                            "AND f.target=experiences.target AND f.after_fp=''" +
                            "),0)) WHERE after_fp<>''"
            );
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
        if (action == null) return;
        if (action.type != AgentAction.Type.CLICK_TEXT && action.type != AgentAction.Type.BACK) return;
        if (beforeFp == null || beforeFp.isEmpty()) return;

        LearningMemoryLeasePolicy.withCurrentLease(action, false, () -> {
            TaskState liveState = new TaskStateRepository(appContext).load();
            if (liveState.mode != TaskState.Mode.RUNNING) return false;
            if (!sameTaskGoal(goal, liveState.goal)) return false;
            if (!mayUseTransitionMemory(liveState.designAnchor)) return false;

            String goalKey = goalScopeKey(liveState.goal);
            String designKey = transitionScopeKey(liveState.designAnchor);
            String target = sanitizeTarget(action.target);
            String after = success && afterFp != null ? afterFp : "";
            long now = System.currentTimeMillis();
            SQLiteDatabase db = getWritableDatabase();

            db.beginTransaction();
            try {
                if (success) {
                    db.execSQL(
                            "INSERT OR IGNORE INTO experiences(goal_key,design_key,before_fp,action_type,target,after_fp,success_count,failure_count,last_at) " +
                                    "VALUES(?,?,?,?,?,?,0,COALESCE((SELECT failure_count FROM experiences " +
                                    "WHERE goal_key=? AND design_key=? AND before_fp=? AND action_type=? AND target=? AND after_fp=''),0),?)",
                            new Object[]{goalKey,designKey,beforeFp,action.type.name(),target,after,
                                    goalKey,designKey,beforeFp,action.type.name(),target,now}
                    );
                    db.execSQL(
                            "UPDATE experiences SET success_count=success_count+1,last_at=? " +
                                    "WHERE goal_key=? AND design_key=? AND before_fp=? AND action_type=? AND target=? AND after_fp=?",
                            new Object[]{now,goalKey,designKey,beforeFp,action.type.name(),target,after}
                    );
                } else {
                    db.execSQL(
                            "INSERT OR IGNORE INTO experiences(goal_key,design_key,before_fp,action_type,target,after_fp,success_count,failure_count,last_at) " +
                                    "VALUES(?,?,?,?,?,'',0,0,?)",
                            new Object[]{goalKey,designKey,beforeFp,action.type.name(),target,now}
                    );
                    db.execSQL(
                            "UPDATE experiences SET failure_count=failure_count+1,last_at=? " +
                                    "WHERE goal_key=? AND design_key=? AND before_fp=? AND action_type=? AND target=?",
                            new Object[]{now,goalKey,designKey,beforeFp,action.type.name(),target}
                    );
                }

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

    public synchronized void recordVerifiedCompletion() {
        TaskState state = new TaskStateRepository(appContext).load();
        if (state.mode != TaskState.Mode.RUNNING) throw new IllegalStateException("verified completion requires RUNNING task");
        if (state.goal == null || state.goal.trim().isEmpty()) throw new IllegalStateException("verified completion requires task goal");
        if (state.designAnchor == null || state.designAnchor.trim().isEmpty()) throw new IllegalStateException("verified completion requires bound design");

        String key = goalScopeKey(state.goal);
        String designKey = completionScopeKey(state.designAnchor);
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO verified_completions(goal_key,design_key,success_count,last_at) VALUES(?,?,0,?)", new Object[]{key,designKey,now});
            db.execSQL("UPDATE verified_completions SET success_count=success_count+1,last_at=? WHERE goal_key=? AND design_key=?", new Object[]{now,key,designKey});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized String summary(String goal, String beforeFp) {
        if (beforeFp == null || beforeFp.isEmpty()) return "none";
        String goalKey = goalScopeKey(goal);
        TaskState state = new TaskStateRepository(appContext).load();
        if (!mayUseTransitionMemory(state.designAnchor)) return "withheld: exact existing design is not bound; transition memory replay is disabled";
        if (!MemoryReplayContinuityPolicy.mayRead(state.mode, state.lastSafeSnapshotHash, beforeFp)) return "withheld: current Canva/design continuity has not been re-proven for memory replay";

        String designKey = transitionScopeKey(state.designAnchor);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT action_type,target,after_fp,success_count,failure_count FROM experiences WHERE goal_key=? AND design_key=? AND before_fp=? ORDER BY (success_count-failure_count) DESC, last_at DESC LIMIT 5",
                new String[]{goalKey,designKey,beforeFp});
        StringBuilder out = new StringBuilder();
        try {
            while (c.moveToNext()) {
                String type = c.getString(0);
                String target = c.getString(1);
                String after = c.getString(2);
                int successes = c.getInt(3);
                int failures = c.getInt(4);
                if (!mayReplayTransition(successes, failures, after)) continue;
                double trust = transitionTrust(successes, failures);
                out.append("exactGoal=true").append(" exactDesign=true").append(" action=").append(type)
                        .append(" target=").append(target).append(" successes=").append(successes)
                        .append(" failures=").append(failures).append(" trust=").append(String.format(Locale.US,"%.2f",trust))
                        .append(" expectedAfter=").append(shortFp(after)).append('\n');
            }
        } finally { c.close(); }

        if (state.designAnchor != null && !state.designAnchor.trim().isEmpty()) {
            int verifiedCompletions = verifiedCompletionCount(goalKey, completionScopeKey(state.designAnchor));
            if (verifiedCompletions > 0) out.append("verifiedDesignGoalCompletions=").append(verifiedCompletions).append(" (final visual QA + exact bound-design proof)\n");
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private int verifiedCompletionCount(String goalKey, String designKey) {
        Cursor c = getReadableDatabase().rawQuery("SELECT success_count FROM verified_completions WHERE goal_key=? AND design_key=?", new String[]{goalKey,designKey});
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    public synchronized int learnedTransitionCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM experiences WHERE success_count>0", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    static boolean sameTaskGoal(String expectedGoal, String liveGoal) {
        String expected = normalize(expectedGoal);
        String live = normalize(liveGoal);
        return !expected.isEmpty() && expected.equals(live);
    }

    static boolean mayUseTransitionMemory(String designAnchor) {
        return designAnchor != null && !designAnchor.trim().isEmpty();
    }

    static boolean mayReplayTransition(int successes, int failures, String afterFp) {
        if (afterFp == null || afterFp.trim().isEmpty()) return false;
        if (successes < MIN_VERIFIED_SUCCESSES_FOR_REPLAY || failures < 0) return false;
        if (successes <= failures) return false;
        return transitionTrust(successes, failures) >= 0.60;
    }

    static double transitionTrust(int successes, int failures) {
        if (successes < 0 || failures < 0) return 0.0;
        return (successes + 1.0) / (successes + failures + 2.0);
    }

    static String transitionScopeKey(String designAnchor) {
        String normalized = normalize(designAnchor);
        return normalized.isEmpty() ? UNBOUND_DESIGN_SCOPE : sha256(normalized);
    }

    static String completionScopeKey(String designAnchor) {
        String normalized = normalize(designAnchor);
        if (normalized.isEmpty()) throw new IllegalArgumentException("completion scope requires bound design");
        return sha256(normalized);
    }

    static String goalScopeKey(String goal) { return sha256(normalize(goal)); }

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
        String x = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
        return x.replace('ı','i').replaceAll("\\s+"," ").trim();
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.US,"%02x",x));
            return b.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }
}
