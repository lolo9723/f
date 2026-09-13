package com.emrah.canvaapprentice;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        out.add(new Node(
                str(n.getViewIdResourceName()), str(n.getClassName()), str(n.getText()),
                str(n.getContentDescription()), r, n.isClickable(), n.isEditable(),
                n.isPassword(), n.isEnabled(), n.isVisibleToUser()
        ));
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, depth + 1);
    }

    public boolean containsSensitiveInput() {
        for (Node n : nodes) {
            if (!n.visibleToUser) continue;
            if (n.password) return true;
            String x = normalize(n.text + " " + n.description);
            if (x.contains("captcha") || x.contains("password") || x.contains("sifre") ||
                    x.contains("verification code") || x.contains("dogrulama kodu") ||
                    x.contains("two-factor") || x.contains("2fa") ||
                    x.contains("one-time code") || x.contains("one time code") ||
                    x.contains("one-time password") || x.contains("one time password") ||
                    x.contains("otp") || x.contains("passcode") ||
                    x.contains("security code") || x.contains("guvenlik kodu") ||
                    x.contains("authenticator code") || x.contains("authenticator app") ||
                    x.contains("tek kullanimlik kod") || x.contains("tek kullanimlik sifre") ||
                    x.contains("verify it's you") || x.contains("verify it is you") ||
                    x.contains("verify your identity") || x.contains("confirm your identity") ||
                    x.contains("enter the code we sent") || x.contains("enter code we sent") ||
                    x.contains("check your phone for a code") || x.contains("check your email for a code") ||
                    x.contains("recovery code") || x.contains("backup code") ||
                    x.contains("sms code") || x.contains("text message code") ||
                    x.contains("code sent by sms") || x.contains("code sent via sms") ||
                    x.contains("passkey") || x.contains("security key") ||
                    x.contains("physical security key") || x.contains("sign in with a passkey") ||
                    x.contains("use your security key") ||
                    x.contains("kimligini dogrula") || x.contains("kimliginizi dogrulayin") ||
                    x.contains("sen oldugunu dogrula") || x.contains("siz oldugunuzu dogrulayin") ||
                    x.contains("gonderdigimiz kodu gir") || x.contains("gonderilen kodu gir") ||
                    x.contains("telefonuna gelen kod") || x.contains("telefonunuza gelen kod") ||
                    x.contains("e-postana gelen kod") || x.contains("e-postaniza gelen kod") ||
                    x.contains("kurtarma kodu") || x.contains("yedek kod") ||
                    x.contains("sms kodu") || x.contains("mesajla gelen kod") ||
                    x.contains("gecis anahtari") || x.contains("guvenlik anahtari")) return true;
        }
        return false;
    }

    public boolean containsText(String anchor) {
        String wanted = normalize(anchor);
        if (wanted.isEmpty()) return false;
        for (Node n : nodes) {
            if (!n.visibleToUser) continue;
            if (normalize(n.text).equals(wanted) || normalize(n.description).equals(wanted)) return true;
        }
        return false;
    }

    public boolean looksLikeCanvaHome() {
        int hits = 0;
        boolean decisiveCreateSurface = false;
        for (Node n : nodes) {
            if (!n.visibleToUser) continue;
            String x = normalize(n.text + " " + n.description);
            if (x.contains("create a design") || x.contains("tasarim olustur")) {
                hits++;
                decisiveCreateSurface = true;
            }
            if (x.equals("projects") || x.equals("projeler")) hits++;
            if (x.equals("templates") || x.equals("sablonlar")) hits++;
            if (x.equals("home") || x.equals("ana sayfa")) hits++;
        }
        return decisiveCreateSurface || hits >= 2;
    }

    public String compactForTeacher() {
        StringBuilder b = new StringBuilder();
        int i = 0;
        final Boolean networkValidated = AndroidNetworkEvidence.currentValidated();
        for (Node n : nodes) {
            if (!n.visibleToUser) continue;
            if (n.text.trim().isEmpty() && n.description.trim().isEmpty() &&
                    !n.clickable && !n.editable) continue;
            String teacherText = OfflineEvidencePolicy.teacherSafeText(
                    n.text, n.description, networkValidated);
            String teacherDescription = OfflineEvidencePolicy.teacherSafeDescription(
                    n.text, n.description, networkValidated);
            b.append(i++).append('|').append(n.className).append('|')
                    .append(clean(teacherText)).append('|').append(clean(teacherDescription)).append('|')
                    .append(boundsForTeacher(n.bounds)).append('|')
                    .append(n.clickable ? "C" : "-").append(n.editable ? "E" : "-").append('\n');
            if (i >= 220) break;
        }
        return b.toString();
    }

    public String stableFingerprint() {
        StringBuilder b = new StringBuilder(packageName);
        int kept = 0;
        for (Node n : nodes) {
            if (!n.visibleToUser) continue;
            if (n.text.trim().isEmpty() && n.description.trim().isEmpty() &&
                    !n.clickable && !n.editable) continue;
            b.append('|').append(n.viewId)
                    .append('|').append(n.text)
                    .append('|').append(n.description)
                    .append('|').append(n.className)
                    .append('|').append(n.clickable ? 'C' : '-')
                    .append(n.editable ? 'E' : '-')
                    .append(n.enabled ? 'N' : 'D')
                    .append('|').append(quantizedBounds(n.bounds));
            // The teacher can target compact rows 0..219. Fingerprint every row the teacher
            // can observe so drift in a late exact-node target cannot hide beyond the old
            // 120-node fingerprint horizon and then execute against a stale UI generation.
            if (++kept >= 220) break;
        }
        return sha256(b.toString());
    }

    private static String boundsForTeacher(Rect r) {
        if (r == null) return "0 0 0 0";
        return r.left + " " + r.top + " " + r.right + " " + r.bottom;
    }

    private static String quantizedBounds(Rect r) {
        if (r == null) return "0,0,0,0";
        final int q = 8;
        return quantize(r.left, q) + "," + quantize(r.top, q) + "," +
                quantize(r.right, q) + "," + quantize(r.bottom, q);
    }

    private static int quantize(int value, int quantum) {
        return Math.round(value / (float) quantum) * quantum;
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.US,"%02x", x));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String str(CharSequence x) { return x == null ? "" : x.toString(); }
    private static String clean(String s) { return s.replace('|','/').replace('\n',' ').trim(); }

    private static String normalize(String s) {
        String x = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}","")
                .toLowerCase(Locale.ROOT)
                .replace('ı','i');
        return x.replaceAll("\\s+"," ").trim();
    }

    public static final class Node {
        public final String viewId, className, text, description;
        public final Rect bounds;
        public final boolean clickable, editable, password, enabled, visibleToUser;

        public Node(String viewId, String className, String text, String description, Rect bounds,
                    boolean clickable, boolean editable, boolean password, boolean enabled) {
            this(viewId, className, text, description, bounds,
                    clickable, editable, password, enabled, true);
        }

        public Node(String viewId, String className, String text, String description, Rect bounds,
                    boolean clickable, boolean editable, boolean password, boolean enabled,
                    boolean visibleToUser) {
            this.viewId = viewId;
            this.className = className;
            this.text = text;
            this.description = description;
            this.bounds = bounds;
            this.clickable = clickable;
            this.editable = editable;
            this.password = password;
            this.enabled = enabled;
            this.visibleToUser = visibleToUser;
        }
    }
}
