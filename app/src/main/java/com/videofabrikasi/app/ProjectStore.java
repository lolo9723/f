package com.videofabrikasi.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class ProjectStore {
    private final SharedPreferences p;
    public ProjectStore(Context context) {
        p = context.getSharedPreferences("video_factory_project", Context.MODE_PRIVATE);
    }
    public void save(String username, String slug, String title, String idea, String status, int version) {
        p.edit().putString("username", username).putString("slug", slug).putString("title", title)
                .putString("idea", idea).putString("status", status).putInt("version", version)
                .putLong("updated", System.currentTimeMillis()).apply();
    }
    public String username() { return p.getString("username", ""); }
    public String slug() { return p.getString("slug", ""); }
    public String title() { return p.getString("title", ""); }
    public String idea() { return p.getString("idea", ""); }
    public String status() { return p.getString("status", "HAZIR"); }
    public int version() { return p.getInt("version", 0); }
    public boolean hasActiveProject() { return !slug().isEmpty(); }
    public void updateStatus(String status) {
        p.edit().putString("status", status).putLong("updated", System.currentTimeMillis()).apply();
    }
}
