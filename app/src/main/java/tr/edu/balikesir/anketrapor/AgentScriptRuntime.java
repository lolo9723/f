package tr.edu.balikesir.anketrapor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Beyaz-listeli Agent Script komutlarının çalışma zamanı. */
final class AgentScriptRuntime {
    static final String STATE_PREF = "yerel_agent_state";
    static final String SCRIPT_RUNNING = "script_running";
    static final String SCRIPT_INDEX = "script_index";
    static final String SCRIPT_STEPS = "agent_script_steps";
    static final String SCRIPT_SAVED = "agent_saved_script";
    static final String SCRIPT_ROWS = "agent_script_rows";
    static final String SCRIPT_LAST_RESULT = "script_last_result";
    private static final String OWN_APP = "tr.edu.balikesir.yerelajan";

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences state;
    private final SecureStore secure;

    private int retryIndex = -1;
    private long retryStart;
    private boolean busy;

    private String carPhase = "";
    private JSONArray carCandidates = new JSONArray();
    private int carCandidateIndex;
    private int carScroll;
    private String carAccumulatedText = "";
    private String carCurrentTitle = "";

    AgentScriptRuntime(AccessibilityService service) {
        this.service = service;
        this.state = service.getSharedPreferences(STATE_PREF, AccessibilityService.MODE_PRIVATE);
        this.secure = new SecureStore(service);
    }

    boolean isRunning() { return state.getBoolean(SCRIPT_RUNNING, false); }

    void onEvent(AccessibilityEvent event) {
        if (!isRunning()) return;
        String pkg = safe(event == null ? null : event.getPackageName());
        if (SafetyPolicy.isBlockedPackage(service, pkg)) {
            stop("Hassas uygulama engellendi: " + pkg, false);
            return;
        }
        handler.removeCallbacks(pumpRunnable);
        handler.postDelayed(pumpRunnable, 110L);
    }

    boolean maybeHandleOwnAppClick(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) return false;
        if (!OWN_APP.equals(safe(event.getPackageName()))) return false;
        String label = String.valueOf(event.getText());
        AccessibilityNodeInfo src = event.getSource();
        if (src != null) {
            label += " " + safe(src.getText());
            src.recycle();
        }
        if (!label.contains("Hazırla") && !label.contains("Çalıştır")) return false;
        handler.postDelayed(this::maybeStart, 260L);
        return true;
    }

    void interrupt() { if (isRunning()) stop("Android erişilebilirlik hizmetini durdurdu.", false); }
    void destroy() { handler.removeCallbacksAndMessages(null); }

    private void maybeStart() {
        if (!inAgentTaskDialog()) return;
        String latest = secure.get("last_text", "");
        String saved = secure.get(SCRIPT_SAVED, "");
        String script;
        if (AgentScriptEngine.looksLikeScript(latest)) {
            script = latest;
            secure.put(SCRIPT_SAVED, script);
        } else script = saved;

        if (script == null || script.trim().isEmpty()) {
            toast("20. modülde önce bir Agent Script seç veya yapıştır.");
            return;
        }
        try {
            AgentScriptEngine.Plan plan = AgentScriptEngine.parse(script);
            if (plan.needsRuntimeClipboard) {
                String clip = clipboard();
                if (clip.trim().equals(script.trim()) || clip.trim().isEmpty()) {
                    toast("Görev kodu kaydedildi. Şimdi çalışma metnini panoya kopyala ve tekrar Çalıştır'a bas.");
                    return;
                }
            }
            secure.put(SCRIPT_STEPS, plan.steps.toString());
            secure.put(SCRIPT_ROWS, "[]");
            state.edit().putBoolean(SCRIPT_RUNNING, true)
                    .putBoolean("running", false).putBoolean("learning", false)
                    .putInt(SCRIPT_INDEX, 0).putString(SCRIPT_LAST_RESULT, "started").apply();
            resetRetry(); resetCar();
            toast("Agent Script başladı: " + plan.name);
            handler.post(pumpRunnable);
        } catch (Exception e) {
            toast("Görev kodu çalıştırılamadı: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private boolean inAgentTaskDialog() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText("Özel Agent Görevi Çalıştır");
            boolean ok = list != null && !list.isEmpty(); recycle(list); return ok;
        } catch (Exception e) { return false; }
        finally { root.recycle(); }
    }

    private final Runnable pumpRunnable = this::pump;

    private void pump() {
        if (!isRunning() || busy) return;
        JSONArray steps = steps(); int index = state.getInt(SCRIPT_INDEX, 0);
        if (index >= steps.length()) { stop("Görev tamamlandı.", true); return; }
        JSONObject step = steps.optJSONObject(index);
        if (step == null) { advance(index, 80); return; }
        String kind = step.optString("kind", "");
        try {
            switch (kind) {
                case "open_app": openApp(step, index); break;
                case "open_url": openUrl(step, index); break;
                case "google_search": googleSearch(step, index); break;
                case "wait": advance(index, Math.max(50, Math.min(30000, step.optInt("ms", 800)))); break;
                case "tap_any": tapAny(step, index); break;
                case "set_any": setAny(step, index); break;
                case "back": service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); advance(index, 700); break;
                case "swipe": swipe(step, index); break;
                case "stop": stop(step.optString("message", "Görev hazır. Son kritik adım sende."), true); break;
                case "share_ajan_album": shareAjanAlbum(index); break;
                case "car_search": carSearch(step, index); break;
                default: stop("Desteklenmeyen komut: " + kind, false); break;
            }
        } catch (Exception e) {
            stop("Görev hatası: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage()), false);
        }
    }

    private JSONArray steps() { try { return new JSONArray(secure.get(SCRIPT_STEPS, "[]")); } catch (Exception e) { return new JSONArray(); } }

    private void openApp(JSONObject step, int index) {
        String pkg = step.optString("package", "").trim();
        if (pkg.isEmpty()) { stop("OPEN_APP paket adı boş.", false); return; }
        if (SafetyPolicy.isBlockedPackage(service, pkg)) { stop("Hassas uygulama engellendi.", false); return; }
        Intent i = service.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { stop("Uygulama bulunamadı: " + pkg, false); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); service.startActivity(i); advance(index, 1200);
    }

    private void openUrl(JSONObject step, int index) {
        String url = step.optString("url", "").trim();
        if (!SafetyPolicy.isSafeUrl(url)) { stop("Güvenli olmayan URL engellendi.", false); return; }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)); i.setPackage("com.android.chrome"); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { service.startActivity(i); } catch (Exception e) { i.setPackage(null); service.startActivity(i); }
        advance(index, 1600);
    }

    private void googleSearch(JSONObject step, int index) throws Exception {
        String q = step.optString("query", "");
        JSONObject x = new JSONObject();
        x.put("url", "https://www.google.com/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8.name()));
        openUrl(x, index);
    }

    private void tapAny(JSONObject step, int index) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) { retry(step, index); return; }
        AccessibilityNodeInfo target = null;
        try {
            target = findAny(root, step.optJSONArray("texts"));
            if (target == null) { retry(step, index); return; }
            if (SafetyPolicy.isProtectedFinal(safe(target.getText())) || SafetyPolicy.isProtectedFinal(safe(target.getContentDescription()))) {
                stop("Son kritik düğmeye dokunmadım.", true); return;
            }
            AccessibilityNodeInfo c = clickableAncestor(target);
            boolean ok = c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (c != null) c.recycle();
            if (ok) { resetRetry(); advance(index, 700); } else retry(step, index);
        } finally { if (target != null) target.recycle(); root.recycle(); }
    }

    private void setAny(JSONObject step, int index) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) { retry(step, index); return; }
        AccessibilityNodeInfo target = null;
        try {
            target = findEditable(root, step.optJSONArray("texts"));
            if (target == null) { retry(step, index); return; }
            String source = step.optString("value_source", "literal");
            String value = "clipboard".equals(source) ? clipboard() : step.optString("value", "");
            if (value.isEmpty() && "clipboard".equals(source)) { stop("Pano boş; metin alanı doldurulmadı.", false); return; }
            Bundle b = new Bundle(); b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) { resetRetry(); advance(index, 500); }
            else retry(step, index);
        } finally { if (target != null) target.recycle(); root.recycle(); }
    }

    private void swipe(JSONObject step, int index) {
        Rect r = screenRect(); float x = r.width() * .5f;
        boolean down = "down".equalsIgnoreCase(step.optString("direction", "up"));
        float sy = down ? r.height() * .30f : r.height() * .78f;
        float ey = down ? r.height() * .78f : r.height() * .30f;
        Path p = new Path(); p.moveTo(x, sy); p.lineTo(x, ey);
        GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 420)).build();
        busy = true;
        service.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) { busy = false; advance(index, 650); }
            @Override public void onCancelled(GestureDescription gestureDescription) { busy = false; retry(step, index); }
        }, null);
    }

    private void shareAjanAlbum(int index) {
        String tree = service.getSharedPreferences(FolderGrantActivity.PREF, AccessibilityService.MODE_PRIVATE).getString(FolderGrantActivity.KEY_URI, "");
        if (tree.isEmpty()) {
            Intent grant = new Intent(service, FolderGrantActivity.class); grant.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); service.startActivity(grant);
            toast("Bir kez 'Ajan' klasörünü seç. Bu izin kalıcı olarak saklanacak.");
            handler.postDelayed(pumpRunnable, 1400); return;
        }
        try {
            ArrayList<Uri> images = listImages(Uri.parse(tree), 20);
            if (images.isEmpty()) { stop("Ajan klasöründe görsel bulunamadı.", false); return; }
            Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE); share.setType("image/*"); share.setPackage("com.instagram.android");
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, images);
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            for (Uri u : images) service.grantUriPermission("com.instagram.android", u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            service.startActivity(share); advance(index, 1800);
        } catch (Exception e) { stop("Ajan klasörü okunamadı: " + e.getMessage(), false); }
    }

    // -------- Araç ilanı araştırma makrosu --------
    private void carSearch(JSONObject step, int index) throws Exception {
        if (carPhase.isEmpty()) {
            resetCar(); carPhase = "discover"; secure.put(SCRIPT_ROWS, "[]");
            String q = step.optString("brand", "Chevrolet") + " " + step.optInt("min_year", 2021) + " satılık araba ilanı Türkiye sahibinden arabam";
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8.name())));
            i.setPackage("com.android.chrome"); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); service.startActivity(i);
            handler.postDelayed(pumpRunnable, 2600); return;
        }
        if ("discover".equals(carPhase)) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) { handler.postDelayed(pumpRunnable, 450); return; }
            try { carCandidates = googleCandidates(root); } finally { root.recycle(); }
            if (carCandidates.length() == 0) {
                if (carScroll++ < 2) { swipePage(false, 650); handler.postDelayed(pumpRunnable, 1000); return; }
                finishCar(step); return;
            }
            carCandidateIndex = 0; carPhase = "open"; handler.post(pumpRunnable); return;
        }
        if ("open".equals(carPhase)) {
            if (rowCount() >= step.optInt("count", 4) || carCandidateIndex >= carCandidates.length()) { finishCar(step); return; }
            carCurrentTitle = carCandidates.optString(carCandidateIndex, "");
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) { handler.postDelayed(pumpRunnable, 400); return; }
            AccessibilityNodeInfo n = null;
            try {
                n = findText(root, carCurrentTitle);
                if (n == null) { carCandidateIndex++; handler.post(pumpRunnable); return; }
                AccessibilityNodeInfo c = clickableAncestor(n); boolean ok = c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (c != null) c.recycle();
                if (!ok) { carCandidateIndex++; handler.post(pumpRunnable); return; }
            } finally { if (n != null) n.recycle(); root.recycle(); }
            carPhase = "scan"; carScroll = 0; carAccumulatedText = ""; handler.postDelayed(pumpRunnable, 2200); return;
        }
        if ("scan".equals(carPhase)) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) { handler.postDelayed(pumpRunnable, 400); return; }
            String url;
            try { carAccumulatedText += "\n" + visibleText(root); url = chromeUrl(root); } finally { root.recycle(); }
            if (url.contains("google.com/search")) { handler.postDelayed(pumpRunnable, 700); return; }
            if (carScroll++ < 2) { swipePage(false, 650); handler.postDelayed(pumpRunnable, 900); return; }
            evaluateCar(step, url, carAccumulatedText, carCurrentTitle);
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); carPhase = "back"; handler.postDelayed(pumpRunnable, 1300); return;
        }
        if ("back".equals(carPhase)) { carCandidateIndex++; carPhase = "open"; handler.postDelayed(pumpRunnable, 350); }
    }

    private void evaluateCar(JSONObject step, String url, String text, String title) {
        String brand = step.optString("brand", "Chevrolet"); int minYear = step.optInt("min_year", 2021), maxKm = step.optInt("max_km", 100000);
        if (!SafetyPolicy.normalize(text).contains(SafetyPolicy.normalize(brand))) return;
        int year = extractYear(text), km = extractKm(text);
        if (year < minYear || km < 0 || km >= maxKm || !SafetyPolicy.isSafeUrl(url)) return;
        JSONArray rows = rows();
        for (int i = 0; i < rows.length(); i++) { JSONArray r = rows.optJSONArray(i); if (r != null && url.equals(r.optString(3))) return; }
        if (rows.length() >= step.optInt("count", 4)) return;
        JSONArray row = new JSONArray(); row.put(title); row.put(String.valueOf(year)); row.put(String.valueOf(km)); row.put(url); rows.put(row);
        secure.put(SCRIPT_ROWS, rows.toString()); toast("Uygun ilan bulundu: " + rows.length() + "/" + step.optInt("count", 4));
    }

    private void finishCar(JSONObject step) {
        try {
            List<String[]> data = SimpleXlsxWriter.rowsFromJson(secure.get(SCRIPT_ROWS, "[]"));
            String path = SimpleXlsxWriter.write(service, step.optString("filename", "Arac_Ilani.xlsx"),
                    new String[]{"İlan", "Model Yılı", "Kilometre", "İlan Linki"}, data, 3);
            int found = data.size(), wanted = step.optInt("count", 4); resetCar();
            if (found >= wanted) stop(found + " uygun ilan bulundu. Excel: " + path, true);
            else stop("Yalnızca " + found + " uygun ilan doğrulanabildi. Excel yine oluşturuldu: " + path, true);
        } catch (Exception e) { resetCar(); stop("Excel oluşturulamadı: " + e.getMessage(), false); }
    }

    private JSONArray googleCandidates(AccessibilityNodeInfo root) {
        JSONArray result = new JSONArray(); Set<String> seen = new HashSet<>(); ArrayList<AccessibilityNodeInfo> q = new ArrayList<>(); q.add(AccessibilityNodeInfo.obtain(root));
        for (int i = 0; i < q.size() && i < 1300 && result.length() < 25; i++) {
            AccessibilityNodeInfo n = q.get(i); String t = safe(n.getText()).trim(); Rect r = new Rect(); n.getBoundsInScreen(r);
            if (t.length() >= 8 && t.length() <= 180 && r.top > dp(130) && n.isVisibleToUser() && hasClickableAncestor(n) && candidateText(t) && seen.add(t)) result.put(t);
            for (int c = 0; c < n.getChildCount(); c++) { AccessibilityNodeInfo ch = n.getChild(c); if (ch != null) q.add(ch); }
        }
        recycle(q); return result;
    }

    private boolean candidateText(String text) {
        String n = SafetyPolicy.normalize(text);
        String[] bad = {"google", "giris yap", "arama", "images", "gorseller", "haritalar", "videolar", "haberler", "daha fazla", "sonraki", "onceki", "ayarlar", "araclar", "reklam"};
        for (String b : bad) if (n.equals(b) || n.startsWith(b + " ")) return false;
        return true;
    }

    private int extractYear(String text) {
        Matcher m = Pattern.compile("(?iu)(?:model\\s*y[ıi]l[ıi]|y[ıi]l|model)\\D{0,25}(20\\d{2})").matcher(text);
        while (m.find()) { int y = number(m.group(1), -1); if (y >= 1990 && y <= 2035) return y; }
        return -1;
    }

    private int extractKm(String text) {
        Pattern[] p = { Pattern.compile("(?iu)(?:kilometre|km)\\D{0,25}([0-9][0-9.\\s]{1,12})"), Pattern.compile("(?iu)([0-9][0-9.\\s]{1,12})\\s*km\\b") };
        for (Pattern x : p) { Matcher m = x.matcher(text); while (m.find()) { int v = number(m.group(1).replace(".", "").replace(" ", ""), -1); if (v >= 0 && v < 2000000) return v; } }
        return -1;
    }

    // -------- yardımcılar --------
    private void retry(JSONObject step, int index) {
        long now = SystemClock.uptimeMillis(); if (retryIndex != index) { retryIndex = index; retryStart = now; }
        if (now - retryStart > step.optInt("timeout", 8000)) { stop("Ekranda gerekli öğe bulunamadı. Adım: " + (index + 1), false); return; }
        handler.postDelayed(pumpRunnable, 350);
    }
    private void resetRetry() { retryIndex = -1; retryStart = 0; }
    private void advance(int index, long delay) { state.edit().putInt(SCRIPT_INDEX, index + 1).apply(); resetRetry(); handler.removeCallbacks(pumpRunnable); handler.postDelayed(pumpRunnable, delay); }
    private void stop(String message, boolean success) {
        state.edit().putBoolean(SCRIPT_RUNNING, false).putString(SCRIPT_LAST_RESULT, success ? "success" : "error").apply();
        busy = false; resetRetry(); resetCar(); handler.removeCallbacks(pumpRunnable); if (message != null && !message.isEmpty()) toast(message);
    }
    private void resetCar() { carPhase = ""; carCandidates = new JSONArray(); carCandidateIndex = 0; carScroll = 0; carAccumulatedText = ""; carCurrentTitle = ""; }
    private JSONArray rows() { try { return new JSONArray(secure.get(SCRIPT_ROWS, "[]")); } catch (Exception e) { return new JSONArray(); } }
    private int rowCount() { return rows().length(); }

    private AccessibilityNodeInfo findAny(AccessibilityNodeInfo root, JSONArray texts) {
        if (texts == null) return null; for (int i = 0; i < texts.length(); i++) { AccessibilityNodeInfo n = findText(root, texts.optString(i)); if (n != null) return n; } return null;
    }
    private AccessibilityNodeInfo findText(AccessibilityNodeInfo root, String wanted) {
        if (wanted == null || wanted.trim().isEmpty()) return null;
        try {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(wanted); String w = SafetyPolicy.normalize(wanted);
            if (found != null) for (AccessibilityNodeInfo n : found) {
                String t = SafetyPolicy.normalize(safe(n.getText())), d = SafetyPolicy.normalize(safe(n.getContentDescription()));
                if (t.equals(w) || t.contains(w) || d.equals(w) || d.contains(w)) { AccessibilityNodeInfo r = AccessibilityNodeInfo.obtain(n); recycle(found); return r; }
            }
            recycle(found);
        } catch (Exception ignored) {}
        return null;
    }
    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root, JSONArray labels) {
        AccessibilityNodeInfo h = findAny(root, labels); if (h != null) { AccessibilityNodeInfo e = editableNear(h); h.recycle(); if (e != null) return e; }
        ArrayList<AccessibilityNodeInfo> q = new ArrayList<>(); q.add(AccessibilityNodeInfo.obtain(root));
        for (int i = 0; i < q.size() && i < 900; i++) { AccessibilityNodeInfo n = q.get(i); if (n.isVisibleToUser() && n.isEditable()) { AccessibilityNodeInfo r = AccessibilityNodeInfo.obtain(n); recycle(q); return r; } for (int c = 0; c < n.getChildCount(); c++) { AccessibilityNodeInfo ch = n.getChild(c); if (ch != null) q.add(ch); } }
        recycle(q); return null;
    }
    private AccessibilityNodeInfo editableNear(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(n);
        for (int i = 0; i < 6 && cur != null; i++) { if (cur.isEditable()) return cur; AccessibilityNodeInfo p = cur.getParent(); cur.recycle(); cur = p; }
        if (cur != null) cur.recycle();
        ArrayList<AccessibilityNodeInfo> q = new ArrayList<>(); q.add(AccessibilityNodeInfo.obtain(n));
        for (int i = 0; i < q.size() && i < 120; i++) { AccessibilityNodeInfo x = q.get(i); if (x.isEditable()) { AccessibilityNodeInfo r = AccessibilityNodeInfo.obtain(x); recycle(q); return r; } for (int c = 0; c < x.getChildCount(); c++) { AccessibilityNodeInfo ch = x.getChild(c); if (ch != null) q.add(ch); } }
        recycle(q); return null;
    }
    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(n); for (int i = 0; i < 8 && cur != null; i++) { if (cur.isClickable()) return cur; AccessibilityNodeInfo p = cur.getParent(); cur.recycle(); cur = p; } if (cur != null) cur.recycle(); return null;
    }
    private boolean hasClickableAncestor(AccessibilityNodeInfo n) { AccessibilityNodeInfo c = clickableAncestor(n); boolean ok = c != null; if (c != null) c.recycle(); return ok; }

    private String clipboard() {
        try { ClipboardManager cm = (ClipboardManager) service.getSystemService(AccessibilityService.CLIPBOARD_SERVICE); ClipData c = cm == null ? null : cm.getPrimaryClip(); if (c == null || c.getItemCount() == 0) return ""; CharSequence s = c.getItemAt(0).coerceToText(service); return s == null ? "" : s.toString(); } catch (Exception e) { return ""; }
    }

    private ArrayList<Uri> listImages(Uri tree, int max) throws Exception {
        ArrayList<Uri> out = new ArrayList<>(); String treeId = DocumentsContract.getTreeDocumentId(tree); Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        Cursor c = service.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC");
        if (c == null) return out;
        try {
            int idI = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID), mimeI = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE), nameI = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (c.moveToNext() && out.size() < max) {
                String id = c.getString(idI), mime = mimeI >= 0 ? c.getString(mimeI) : "", name = nameI >= 0 ? c.getString(nameI) : "";
                boolean image = mime != null && mime.startsWith("image/"); if (!image && name != null) { String x = name.toLowerCase(Locale.ROOT); image = x.endsWith(".jpg") || x.endsWith(".jpeg") || x.endsWith(".png") || x.endsWith(".webp"); }
                if (image) out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
            }
        } finally { c.close(); }
        return out;
    }

    private String visibleText(AccessibilityNodeInfo root) {
        StringBuilder b = new StringBuilder(); ArrayList<AccessibilityNodeInfo> q = new ArrayList<>(); q.add(AccessibilityNodeInfo.obtain(root));
        for (int i = 0; i < q.size() && i < 1600; i++) { AccessibilityNodeInfo n = q.get(i); if (n.isVisibleToUser()) { String t = safe(n.getText()).trim(), d = safe(n.getContentDescription()).trim(); if (!t.isEmpty()) b.append(t).append('\n'); if (!d.isEmpty() && !d.equals(t)) b.append(d).append('\n'); } for (int c = 0; c < n.getChildCount(); c++) { AccessibilityNodeInfo ch = n.getChild(c); if (ch != null) q.add(ch); } }
        recycle(q); return b.toString();
    }
    private String chromeUrl(AccessibilityNodeInfo root) {
        try { List<AccessibilityNodeInfo> f = root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar"); if (f != null && !f.isEmpty()) { String u = safe(f.get(0).getText()); recycle(f); if (!u.startsWith("http")) u = "https://" + u; return u; } recycle(f); } catch (Exception ignored) {} return "";
    }
    private void swipePage(boolean down, long duration) {
        Rect r = screenRect(); float x = r.width() * .5f, sy = down ? r.height() * .3f : r.height() * .78f, ey = down ? r.height() * .78f : r.height() * .30f;
        Path p = new Path(); p.moveTo(x, sy); p.lineTo(x, ey); service.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, duration)).build(), null, null);
    }
    private Rect screenRect() {
        Rect r = new Rect(); AccessibilityNodeInfo root = service.getRootInActiveWindow(); if (root != null) { root.getBoundsInScreen(r); root.recycle(); }
        if (r.width() <= 0 || r.height() <= 0) r.set(0, 0, service.getResources().getDisplayMetrics().widthPixels, service.getResources().getDisplayMetrics().heightPixels); return r;
    }
    private int dp(int v) { return Math.round(v * service.getResources().getDisplayMetrics().density); }
    private int number(String s, int def) { try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return def; } }
    private void toast(String s) { Toast.makeText(service, s, Toast.LENGTH_LONG).show(); }
    private static String safe(CharSequence s) { return s == null ? "" : s.toString(); }
    private static void recycle(List<AccessibilityNodeInfo> list) { if (list != null) for (AccessibilityNodeInfo n : list) if (n != null) n.recycle(); }
}
