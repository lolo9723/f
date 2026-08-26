package com.videofabrikasi.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ProjectStore {
    private static final int MAX_HISTORY = 500;
    private final SharedPreferences p;

    public ProjectStore(Context context) {
        p = context.getSharedPreferences("video_factory_project", Context.MODE_PRIVATE);
        migrateLegacyIfNeeded();
    }

    public synchronized void save(String username, String slug, String title, String idea, String status, int version) {
        long now = System.currentTimeMillis();
        writeCurrent(username, slug, title, idea, status, version, now);
        try {
            JSONArray old = historyArray();
            JSONArray next = new JSONArray();
            next.put(record(username, slug, title, idea, status, version, now));
            for (int i = 0; i < old.length() && next.length() < MAX_HISTORY; i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && !slug.equals(item.optString("slug", ""))) next.put(item);
            }
            p.edit().putString("history_json", next.toString()).apply();
        } catch (Exception ignored) {
            // Current project was already saved; history failure must never corrupt active work.
        }
    }

    public synchronized void updateStatus(String status) {
        long now = System.currentTimeMillis();
        p.edit().putString("status", status).putLong("updated", now).apply();
        String activeSlug = slug();
        if (activeSlug.isEmpty()) return;
        try {
            JSONArray old = historyArray();
            JSONArray next = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item == null) continue;
                if (activeSlug.equals(item.optString("slug", ""))) {
                    item.put("status", status);
                    item.put("updated", now);
                }
                next.put(item);
            }
            p.edit().putString("history_json", next.toString()).apply();
        } catch (Exception ignored) {}
    }

    public synchronized boolean move(int delta) {
        try {
            JSONArray a = historyArray();
            if (a.length() == 0) return false;
            int current = indexOf(a, slug());
            if (current < 0) current = 0;
            int target = current + delta;
            if (target < 0) target = a.length() - 1;
            if (target >= a.length()) target = 0;
            JSONObject item = a.optJSONObject(target);
            if (item == null) return false;
            loadCurrent(item);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized int historyCount() {
        return historyArray().length();
    }

    public synchronized int historyPosition() {
        JSONArray a = historyArray();
        if (a.length() == 0) return 0;
        int i = indexOf(a, slug());
        return i < 0 ? 1 : i + 1;
    }

    public synchronized String historySummary(int maxItems) {
        JSONArray a = historyArray();
        StringBuilder b = new StringBuilder();
        int n = Math.min(Math.max(0, maxItems), a.length());
        for (int i = 0; i < n; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (b.length() > 0) b.append('\n');
            b.append(i + 1).append(". ")
                    .append(o.optString("slug", "proje"))
                    .append(" — ").append(o.optString("status", "BİLİNMİYOR"));
        }
        return b.toString();
    }

    public String username() { return p.getString("username", ""); }
    public String slug() { return p.getString("slug", ""); }
    public String title() { return p.getString("title", ""); }
    public String idea() { return p.getString("idea", ""); }
    public String status() { return p.getString("status", "HAZIR"); }
    public int version() { return p.getInt("version", 0); }
    public long updated() { return p.getLong("updated", 0L); }
    public boolean hasActiveProject() { return !slug().isEmpty(); }

    synchronized void clearForTests() {
        p.edit().clear().commit();
    }

    private void migrateLegacyIfNeeded() {
        if (p.getString("history_json", "").isEmpty() && !p.getString("slug", "").isEmpty()) {
            save(username(), slug(), title(), idea(), status(), version());
        }
    }

    private JSONArray historyArray() {
        try {
            String raw = p.getString("history_json", "[]");
            return new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private int indexOf(JSONArray a, String targetSlug) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && targetSlug.equals(o.optString("slug", ""))) return i;
        }
        return -1;
    }

    private JSONObject record(String username, String slug, String title, String idea,
                              String status, int version, long updated) throws Exception {
        JSONObject o = new JSONObject();
        o.put("username", username);
        o.put("slug", slug);
        o.put("title", title);
        o.put("idea", idea);
        o.put("status", status);
        o.put("version", version);
        o.put("updated", updated);
        return o;
    }

    private void writeCurrent(String username, String slug, String title, String idea,
                              String status, int version, long updated) {
        p.edit().putString("username", username).putString("slug", slug).putString("title", title)
                .putString("idea", idea).putString("status", status).putInt("version", version)
                .putLong("updated", updated).apply();
    }

    private void loadCurrent(JSONObject o) {
        writeCurrent(o.optString("username", ""), o.optString("slug", ""),
                o.optString("title", ""), o.optString("idea", ""),
                o.optString("status", "HAZIR"), o.optInt("version", 0),
                o.optLong("updated", System.currentTimeMillis()));
    }
}
