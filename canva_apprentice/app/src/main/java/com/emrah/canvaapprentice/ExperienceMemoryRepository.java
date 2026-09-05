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
    private static final int VERSION = 2;
    private static final int MAX_ROWS = 500;
    private final Context appContext;

    public ExperienceMemoryRepository(Context context) {
        super(context, DB, null, VERSION);
        appContext = context.getApplicationContext();
        VerifiedCompletionMemoryHook.install(this::recordVerifiedCompletion);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE experiences (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "goal_key TEXT NOT NULL," +
                "before_fp TEXT NOT NULL," +
                "action_type TEXT NOT NULL," +
                "target TEXT NOT NULL," +
                "after_fp TEXT NOT NULL," +
                "success_count INTEGER NOT NULL DEFAULT 0," +
                "failure_count INTEGER NOT NULL DEFAULT 0," +
                "last_at INTEGER NOT NULL," +
                "UNIQUE(goal_key,before_fp,action_type,target,after_fp))");
        db.execSQL("CREATE INDEX idx_exp_before ON experiences(before_fp)");
        db.execSQL("CREATE INDEX idx_exp_goal_before ON experiences(goal_key,before_fp)");
        createVerifiedCompletionsTable(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createVerifiedCompletionsTable(db);
    }

    private static void createVerifiedCompletionsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS verified_completions (" +
                "goal_key TEXT PRIMARY KEY," +
                "success_count INTEGER NOT NULL DEFAULT 0," +
                "last_at INTEGER NOT NULL)");
    }

    public synchronized void record(boolean success, String goal, String beforeFp,
                                    AgentAction action, String afterFp) {
        if (!LearningMemoryLeasePolicy.canRecord(action)) return;
        // Learn navigation structure, not user-authored content.
        if (action.type != AgentAction.Type.CLICK_TEXT && action.type != AgentAction.Type.BACK) return;
        if (beforeFp == null || beforeFp.isEmpty()) return;

        String goalKey = goalKey(goal);
        String target = sanitizeTarget(action.target);
        String after = afterFp == null ? "" : afterFp;
        SQLiteDatabase db = getWritableDatabase();

        db.beginTransaction();
        try {
            db.execSQL(
                    "INSERT OR IGNORE INTO experiences(goal_key,before_fp,action_type,target,after_fp,success_count,failure_count,last_at) " +
                            "VALUES(?,?,?,?,?,0,0,?)",
                    new Object[]{goalKey,beforeFp,action.type.name(),target,after,System.currentTimeMillis()}
            );
            db.execSQL(
                    "UPDATE experiences SET success_count=success_count+?, failure_count=failure_count+?, last_at=? " +
                            "WHERE goal_key=? AND before_fp=? AND action_type=? AND target=? AND after_fp=?",
                    new Object[]{success ? 1 : 0, success ? 0 : 1, System.currentTimeMillis(),
                            goalKey,beforeFp,action.type.name(),target,after}
            );
            db.execSQL(
                    "DELETE FROM experiences WHERE id NOT IN " +
                            "(SELECT id FROM experiences ORDER BY last_at DESC LIMIT " + MAX_ROWS + ")"
            );
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Persists only a final success that is already executing inside FinalDoneCommitGuard's
     * exact execution-lease and current-teacher-session boundary. The current task must still
     * be RUNNING and must have a bound design anchor; otherwise persistence fails and STOP is
     * prevented by the guard.
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
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL(
                    "INSERT OR IGNORE INTO verified_completions(goal_key,success_count,last_at) VALUES(?,0,?)",
                    new Object[]{key,now}
            );
            db.execSQL(
                    "UPDATE verified_completions SET success_count=success_count+1,last_at=? WHERE goal_key=?",
                    new Object[]{now,key}
            );
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized String summary(String goal, String beforeFp) {
        if (beforeFp == null || beforeFp.isEmpty()) return "none";
        String goalKey = goalKey(goal);
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT goal_key,action_type,target,after_fp,success_count,failure_count " +
                        "FROM experiences WHERE before_fp=? " +
                        "ORDER BY CASE WHEN goal_key=? THEN 0 ELSE 1 END, " +
                        "(success_count-failure_count) DESC, last_at DESC LIMIT 5",
                new String[]{beforeFp,goalKey}
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

        int verifiedCompletions = verifiedCompletionCount(goalKey);
        if (verifiedCompletions > 0) {
            out.append("verifiedGoalCompletions=").append(verifiedCompletions)
                    .append(" (final visual QA + bound-design proof)\n");
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private int verifiedCompletionCount(String goalKey) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT success_count FROM verified_completions WHERE goal_key=?",
                new String[]{goalKey}
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
