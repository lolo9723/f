package com.emrah.canvaapprentice;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class UiTreeSnapshot {
    public final String packageName;
    public final List<Node> nodes;
    public final long timestampMs;

    public UiTreeSnapshot(String packageName, List<Node> nodes, long timestampMs) {
        this.packageName = packageName == null ? "" : packageName;
        this.nodes = nodes;
        this.timestampMs = timestampMs;
    }

    public static UiTreeSnapshot capture(AccessibilityNodeInfo root) {
        List<Node> out = new ArrayList<>();
        String pkg = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
        walk(root, out, 0);
        return new UiTreeSnapshot(pkg, out, System.currentTimeMillis());
    }

    private static void walk(AccessibilityNodeInfo n, List<Node> out, int depth) {
        if (n == null || depth > 60 || out.size() > 2500) return;
        Rect r = new Rect(); n.getBoundsInScreen(r);
        out.add(new Node(
                str(n.getViewIdResourceName()), str(n.getClassName()), str(n.getText()),
                str(n.getContentDescription()), r, n.isClickable(), n.isEditable(), n.isPassword(), n.isEnabled()
        ));
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, depth + 1);
    }

    public boolean containsSensitiveInput() {
        for (Node n : nodes) {
            if (n.password) return true;
            String x = (n.text + " " + n.description).toLowerCase();
            if (x.contains("captcha") || x.contains("password") || x.contains("şifre") ||
                x.contains("verification code") || x.contains("doğrulama kodu") || x.contains("two-factor")) return true;
        }
        return false;
    }

    public String compactForTeacher() {
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (Node n : nodes) {
            if (n.text.trim().isEmpty() && n.description.trim().isEmpty() && !n.clickable && !n.editable) continue;
            b.append(i++).append('|').append(n.className).append('|')
                    .append(clean(n.text)).append('|').append(clean(n.description)).append('|')
                    .append(n.bounds.flattenToString()).append('|')
                    .append(n.clickable ? "C" : "-").append(n.editable ? "E" : "-").append('\n');
            if (i >= 220) break;
        }
        return b.toString();
    }

    public String stableFingerprint() {
        StringBuilder b = new StringBuilder(packageName);
        int kept = 0;
        for (Node n : nodes) {
            if (!n.text.trim().isEmpty() || !n.description.trim().isEmpty()) {
                b.append('|').append(n.text).append('|').append(n.description).append('|').append(n.className);
                if (++kept >= 80) break;
            }
        }
        return sha256(b.toString());
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(); for (byte x : d) b.append(String.format("%02x", x)); return b.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    private static String str(CharSequence x) { return x == null ? "" : x.toString(); }
    private static String clean(String s) { return s.replace('|','/').replace('\n',' ').trim(); }

    public static final class Node {
        public final String viewId, className, text, description;
        public final Rect bounds;
        public final boolean clickable, editable, password, enabled;
        public Node(String viewId, String className, String text, String description, Rect bounds,
                    boolean clickable, boolean editable, boolean password, boolean enabled) {
            this.viewId=viewId; this.className=className; this.text=text; this.description=description;
            this.bounds=bounds; this.clickable=clickable; this.editable=editable; this.password=password; this.enabled=enabled;
        }
    }
}
