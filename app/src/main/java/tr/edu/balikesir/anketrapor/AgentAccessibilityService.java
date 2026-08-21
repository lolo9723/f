package tr.edu.balikesir.anketrapor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import android.provider.MediaStore;
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

/**
 * Yerel Ajan erişilebilirlik servisi.
 * - Normal 1-9 modülleri TouchAgentServiceV2'ye devredilir.
 * - 20. modülde güvenli, beyaz-listeli Agent Script motoru çalışır.
 */
public class AgentAccessibilityService extends TouchAgentServiceV2 {
    private static final String STATE_PREF = "yerel_agent_state";
    private static final String KEY_LEARNING = "learning";
    private static final String KEY_LEARNING_MODULE = "learning_module";
    private static final String KEY_TARGET_PACKAGE = "target_package";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_RUNNING_MODULE = "running_module";
    private static final String KEY_RUNNING_TEXT = "running_text";
    private static final String KEY_RUNNING_FILES = "running_files";
    private static final String KEY_STEP_INDEX = "step_index";
    private static final String CAL_PREFIX = "calibration_";

    private static final String OWN_APP = "tr.edu.balikesir.yerelajan";
    private static final String SCRIPT_RUNNING = "script_running";
    private static final String SCRIPT_INDEX = "script_index";
    private static final String SCRIPT_STEPS = "agent_script_steps";
    private static final String SCRIPT_SAVED = "agent_saved_script";
    private static final String SCRIPT_ROWS = "agent_script_rows";
    private static final String SCRIPT_LAST_RESULT = "script_last_result";

    private final Handler scriptHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences scriptState;
    private SecureStore scriptSecure;
    private int retryIndex = -1;
    private long retryStart = 0L;
    private boolean scriptBusy = false;

    // Araç arama alt-durumu
    private String carPhase = "";
    private JSONArray carCandidates = new JSONArray();
    private int carCandidateIndex = 0;
    private int carScroll = 0;
    private String carAccumulatedText = "";
    private String carCurrentTitle = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        ensureScriptStores();
        // Paket filtresini kaldırıyoruz; içerik yalnızca aktif görev hedefinde işlenir.
        // Bu sayede gelecekte yeni uygulamalar için yeni APK gerekmez.
        AccessibilityServiceInfo info = getServiceInfo();
        info.packageNames = null;
        setServiceInfo(info);
        if (!AgentScriptEngine.selfTest()) toast("Agent Script öz testi başarısız. 20. modül güvenli biçimde devre dışı.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        ensureScriptStores();
        String pkg = safe(event.getPackageName());

        if (scriptState.getBoolean(SCRIPT_RUNNING, false)) {
            if (SafetyPolicy.isBlockedPackage(this, pkg)) {
                stopScript("Hassas uygulama engellendi: " + pkg, false);
                return;
            }
            scriptHandler.removeCallbacks(scriptPump);
            scriptHandler.postDelayed(scriptPump, 110L);
            return;
        }

        // 20. modülün Hazırla / Çalıştır tıklamasını yakala.
        if (OWN_APP.equals(pkg) && event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            String label = safe(event.getText());
            String sourceText = "";
            AccessibilityNodeInfo src = event.getSource();
            if (src != null) {
                sourceText = safe(src.getText());
                src.recycle();
            }
            String joined = (label + " " + sourceText).trim();
            if (joined.contains("Hazırla") || joined.contains("Çalıştır")) {
                scriptHandler.postDelayed(this::maybeStartAgentScript, 260L);
            }
        }

        super.onAccessibilityEvent(event);
    }

    @Override
    public void onInterrupt() {
        stopScript("Android erişilebilirlik hizmetini durdurdu.", false);
        super.onInterrupt();
    }

    @Override
    public void onDestroy() {
        scriptHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void ensureScriptStores() {
        if (scriptState == null) scriptState = getSharedPreferences(STATE_PREF, MODE_PRIVATE);
        if (scriptSecure == null) scriptSecure = new SecureStore(this);
    }

    private boolean inAgentTaskDialog() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText("Özel Agent Görevi Çalıştır");
            boolean ok = list != null && !list.isEmpty();
            recycle(list);
            return ok;
        } catch (Exception e) {
            return false;
        } finally { root.recycle(); }
    }

    private void maybeStartAgentScript() {
        ensureScriptStores();
        if (!inAgentTaskDialog()) return;
        String latest = scriptSecure.get("last_text", "");
        String saved = scriptSecure.get(SCRIPT_SAVED, "");
        String script;
        if (AgentScriptEngine.looksLikeScript(latest)) {
            script = latest;
            scriptSecure.put(SCRIPT_SAVED, script);
        } else {
            script = saved;
        }
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
            scriptSecure.put(SCRIPT_STEPS, plan.steps.toString());
            scriptSecure.put(SCRIPT_ROWS, "[]");
            scriptState.edit()
                    .putBoolean(SCRIPT_RUNNING, true)
                    .putBoolean(KEY_RUNNING, false)
                    .putBoolean(KEY_LEARNING, false)
                    .putInt(SCRIPT_INDEX, 0)
                    .putString(SCRIPT_LAST_RESULT, "started")
                    .apply();
            resetRetry();
            resetCar();
            toast("Agent Script başladı: " + plan.name);
            scriptHandler.post(scriptPump);
        } catch (Exception e) {
            toast("Görev kodu çalıştırılamadı: " + e.getMessage());
        }
    }

    private final Runnable scriptPump = new Runnable() {
        @Override public void run() { pumpScript(); }
    };

    private void pumpScript() {
        ensureScriptStores();
        if (!scriptState.getBoolean(SCRIPT_RUNNING, false) || scriptBusy) return;
        JSONArray steps = scriptSteps();
        int index = scriptState.getInt(SCRIPT_INDEX, 0);
        if (index >= steps.length()) {
            stopScript("Görev tamamlandı.", true);
            return;
        }
        JSONObject step = steps.optJSONObject(index);
        if (step == null) { advanceScript(index, 80); return; }
        String kind = step.optString("kind", "");
        try {
            switch (kind) {
                case "open_app": runOpenApp(step, index); break;
                case "open_url": runOpenUrl(step, index); break;
                case "google_search": runGoogleSearch(step, index); break;
                case "wait": advanceScript(index, Math.max(50, Math.min(30000, step.optInt("ms", 800)))); break;
                case "tap_any": runTapAny(step, index); break;
                case "set_any": runSetAny(step, index); break;
                case "back": performGlobalAction(GLOBAL_ACTION_BACK); advanceScript(index, 700); break;
                case "swipe": runSwipe(step, index); break;
                case "stop": stopScript(step.optString("message", "Görev hazır. Son kritik adım sende."), true); break;
                case "share_ajan_album": runShareAjanAlbum(step, index); break;
                case "car_search": runCarSearch(step, index); break;
                default: stopScript("Desteklenmeyen komut: " + kind, false); break;
            }
        } catch (Exception e) {
            stopScript("Görev hatası: " + e.getClass().getSimpleName() + " - " + e.getMessage(), false);
        }
    }

    private JSONArray scriptSteps() {
        try { return new JSONArray(scriptSecure.get(SCRIPT_STEPS, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private void runOpenApp(JSONObject step, int index) {
        String pkg = step.optString("package", "").trim();
        if (pkg.isEmpty()) { stopScript("OPEN_APP paket adı boş.", false); return; }
        if (SafetyPolicy.isBlockedPackage(this, pkg)) { stopScript("Hassas uygulama engellendi.", false); return; }
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { stopScript("Uygulama bulunamadı: " + pkg, false); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        advanceScript(index, 1200);
    }

    private void runOpenUrl(JSONObject step, int index) {
        String url = step.optString("url", "").trim();
        if (!SafetyPolicy.isSafeUrl(url)) { stopScript("Güvenli olmayan URL engellendi.", false); return; }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.setPackage("com.android.chrome"); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { i.setPackage(null); startActivity(i); }
        advanceScript(index, 1600);
    }

    private void runGoogleSearch(JSONObject step, int index) throws Exception {
        String q = step.optString("query", "");
        String url = "https://www.google.com/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8.name());
        JSONObject x = new JSONObject(); x.put("url", url);
        runOpenUrl(x, index);
    }

    private void runTapAny(JSONObject step, int index) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { retry(step, index); return; }
        AccessibilityNodeInfo target = null;
        try {
            target = findAny(root, step.optJSONArray("texts"));
            if (target == null) { retry(step, index); return; }
            String t = safe(target.getText()); String d = safe(target.getContentDescription());
            if (SafetyPolicy.isProtectedFinal(t) || SafetyPolicy.isProtectedFinal(d)) {
                stopScript("Son kritik düğmeye dokunmadım.", true); return;
            }
            AccessibilityNodeInfo clickable = clickableAncestor(target);
            boolean ok = clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (clickable != null) clickable.recycle();
            if (ok) { resetRetry(); advanceScript(index, 700); }
            else retry(step, index);
        } finally {
            if (target != null) target.recycle();
            root.recycle();
        }
    }

    private void runSetAny(JSONObject step, int index) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { retry(step, index); return; }
        AccessibilityNodeInfo target = null;
        try {
            target = findEditable(root, step.optJSONArray("texts"));
            if (target == null) { retry(step, index); return; }
            String source = step.optString("value_source", "literal");
            String value = "clipboard".equals(source) ? clipboard() : step.optString("value", "");
            if (value.isEmpty() && "clipboard".equals(source)) { stopScript("Pano boş; metin alanı doldurulmadı.", false); return; }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                resetRetry(); advanceScript(index, 500);
            } else retry(step, index);
        } finally {
            if (target != null) target.recycle();
            root.recycle();
        }
    }

    private void runSwipe(JSONObject step, int index) {
        Rect dm = new Rect(); getRootInActiveWindowBounds(dm);
        float x = dm.width() * .5f;
        boolean down = "down".equalsIgnoreCase(step.optString("direction", "up"));
        float sy = down ? dm.height() * .30f : dm.height() * .78f;
        float ey = down ? dm.height() * .78f : dm.height() * .30f;
        Path p = new Path(); p.moveTo(x, sy); p.lineTo(x, ey);
        GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 420)).build();
        scriptBusy = true;
        dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) { scriptBusy = false; advanceScript(index, 650); }
            @Override public void onCancelled(GestureDescription gestureDescription) { scriptBusy = false; retry(step, index); }
        }, null);
    }

    private void runShareAjanAlbum(JSONObject step, int index) {
        String tree = getSharedPreferences(FolderGrantActivity.PREF, MODE_PRIVATE).getString(FolderGrantActivity.KEY_URI, "");
        if (tree.isEmpty()) {
            Intent grant = new Intent(this, FolderGrantActivity.class); grant.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(grant);
            toast("Bir kez 'Ajan' klasörünü seç. Bu izin kalıcı olarak saklanacak.");
            scriptHandler.postDelayed(scriptPump, 1200L);
            return;
        }
        try {
            ArrayList<Uri> images = listImages(Uri.parse(tree), 20);
            if (images.isEmpty()) { stopScript("Ajan klasöründe görsel bulunamadı.", false); return; }
            Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
            share.setType("image/*"); share.setPackage("com.instagram.android");
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, images);
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            for (Uri u : images) grantUriPermission("com.instagram.android", u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(share);
            advanceScript(index, 1800);
        } catch (Exception e) { stopScript("Ajan klasörü okunamadı: " + e.getMessage(), false); }
    }

    // ---------------- GOOGLE ARAÇ ARAŞTIRMASI ----------------

    private void runCarSearch(JSONObject step, int index) throws Exception {
        if (carPhase.isEmpty()) {
            carPhase = "discover"; carCandidates = new JSONArray(); carCandidateIndex = 0; carScroll = 0; carAccumulatedText = "";
            scriptSecure.put(SCRIPT_ROWS, "[]");
            String brand = step.optString("brand", "Chevrolet");
            int minYear = step.optInt("min_year", 2021);
            String q = brand + " " + minYear + " satılık araba ilanı Türkiye sahibinden arabam";
            String url = "https://www.google.com/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8.name());
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)); i.setPackage("com.android.chrome"); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i);
            scriptHandler.postDelayed(scriptPump, 2600L); return;
        }
        if ("discover".equals(carPhase)) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { scriptHandler.postDelayed(scriptPump, 500); return; }
            try { carCandidates = collectGoogleCandidates(root); }
            finally { root.recycle(); }
            if (carCandidates.length() == 0) {
                if (carScroll++ < 2) { swipePage(false, 700); scriptHandler.postDelayed(scriptPump, 1000); return; }
                finishCar(step, index); return;
            }
            carCandidateIndex = 0; carPhase = "open"; scriptHandler.post(scriptPump); return;
        }
        if ("open".equals(carPhase)) {
            if (rowCount() >= step.optInt("count", 4)) { finishCar(step, index); return; }
            if (carCandidateIndex >= carCandidates.length()) { finishCar(step, index); return; }
            carCurrentTitle = carCandidates.optString(carCandidateIndex, "");
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { scriptHandler.postDelayed(scriptPump, 400); return; }
            AccessibilityNodeInfo n = null;
            try {
                n = findExactOrContains(root, carCurrentTitle);
                if (n == null) { carCandidateIndex++; scriptHandler.post(scriptPump); return; }
                AccessibilityNodeInfo c = clickableAncestor(n);
                boolean ok = c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (c != null) c.recycle();
                if (!ok) { carCandidateIndex++; scriptHandler.post(scriptPump); return; }
            } finally { if (n != null) n.recycle(); root.recycle(); }
            carPhase = "scan"; carScroll = 0; carAccumulatedText = "";
            scriptHandler.postDelayed(scriptPump, 2200); return;
        }
        if ("scan".equals(carPhase)) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { scriptHandler.postDelayed(scriptPump, 400); return; }
            String url;
            try {
                carAccumulatedText += "\n" + visibleText(root);
                url = chromeUrl(root);
            } finally { root.recycle(); }
            if (url.contains("google.com/search")) { scriptHandler.postDelayed(scriptPump, 700); return; }
            if (carScroll++ < 2) { swipePage(false, 650); scriptHandler.postDelayed(scriptPump, 900); return; }
            evaluateCar(step, url, carAccumulatedText, carCurrentTitle);
            performGlobalAction(GLOBAL_ACTION_BACK);
            carPhase = "back"; scriptHandler.postDelayed(scriptPump, 1300); return;
        }
        if ("back".equals(carPhase)) {
            carCandidateIndex++; carPhase = "open"; scriptHandler.postDelayed(scriptPump, 350);
        }
    }

    private void evaluateCar(JSONObject step, String url, String text, String title) {
        String brand = step.optString("brand", "Chevrolet"); int minYear = step.optInt("min_year", 2021); int maxKm = step.optInt("max_km", 100000);
        if (!SafetyPolicy.normalize(text).contains(SafetyPolicy.normalize(brand))) return;
        int year = extractYear(text); int km = extractKm(text);
        if (year < minYear || km < 0 || km >= maxKm || !SafetyPolicy.isSafeUrl(url)) return;
        JSONArray rows = rows();
        for (int i = 0; i < rows.length(); i++) { JSONArray r = rows.optJSONArray(i); if (r != null && url.equals(r.optString(3))) return; }
        if (rows.length() >= step.optInt("count", 4)) return;
        JSONArray row = new JSONArray(); row.put(title); row.put(String.valueOf(year)); row.put(String.valueOf(km)); row.put(url); rows.put(row);
        scriptSecure.put(SCRIPT_ROWS, rows.toString());
        toast("Uygun ilan bulundu: " + rows.length() + "/" + step.optInt("count", 4));
    }

    private void finishCar(JSONObject step, int index) {
        try {
            List<String[]> data = SimpleXlsxWriter.rowsFromJson(scriptSecure.get(SCRIPT_ROWS, "[]"));
            String path = SimpleXlsxWriter.write(this, step.optString("filename", "Arac_Ilani.xlsx"),
                    new String[]{"İlan", "Model Yılı", "Kilometre", "İlan Linki"}, data, 3);
            int found = data.size(); int wanted = step.optInt("count", 4);
            resetCar();
            if (found >= wanted) stopScript(found + " uygun ilan bulundu. Excel: " + path, true);
            else stopScript("Yalnızca " + found + " uygun ilan doğrulanabildi. Excel yine oluşturuldu: " + path, true);
        } catch (Exception e) { resetCar(); stopScript("Excel oluşturulamadı: " + e.getMessage(), false); }
    }

    private JSONArray collectGoogleCandidates(AccessibilityNodeInfo root) {
        JSONArray result = new JSONArray(); Set<String> seen = new HashSet<>(); ArrayList<AccessibilityNodeInfo> q = new ArrayList<>(); q.add(AccessibilityNodeInfo.obtain(root));
        for (int i = 0; i < q.size() && i < 1300 && result.length() < 25; i++) {
            AccessibilityNodeInfo n = q.get(i); String t = safe(n.getText()).trim(); Rect r = new Rect(); n.getBoundsInScreen(r);
            if (t.length() >= 8 && t.length() <= 180 && r.top > dp(130) && n.isVisibleToUser() && hasClickableAncestor(n) && isCandidateText(t) && seen.add(t)) result.put(t);
            for (int c = 0; c < n.getChildCount(); c++) { AccessibilityNodeInfo ch = n.getChild(c); if (ch != null) q.add(ch); }
        }
        recycle(q); return result;
    }

    private boolean isCandidateText(String t) {
        String n = SafetyPolicy.normalize(t);
        String[] bad = {"google", "giris yap", "arama", "images", "gorseller", "haritalar", "videolar", "haberler", "daha fazla", "sonraki", "onceki", "ayarlar", "araclar", "reklam"};
        for (String b : bad) if (n.equals(b) || n.startsWith(b + " ")) return false;
        return true;
    }

    private int extractYear(String text) {
        Matcher m = Pattern.compile("(?iu)(?:model\\s*y[ıi]l[ıi]|y[ıi]l|model)\\D{0,25}(20\\d{2})").matcher(text);
        while (m.find()) { int y = toInt(m.group(1), -1); if (y >= 1990 && y <= 2035) return y; }
        return -1;
    }

    private int extractKm(String text) {
        Pattern[] ps = {
                Pattern.compile("(?iu)(?:kilometre|km)\\D{0,25}([0-9][0-9.\\s]{1,12})"),
                Pattern.compile("(?iu)([0-9][0-9.\\s]{1,12})\\s*km\\b")
        };
        for (Pattern p : ps) {
            Matcher m = p.matcher(text); while (m.find()) { int v = toInt(m.group(1).replace(".", "").replace(" ", ""), -1); if (v >= 0 && v < 2000000) return v; }
        }
        return -1;
    }

    // ---------------- YARDIMCILAR ----------------

    private void retry(JSONObject step, int index) {
        long now = SystemClock.uptimeMillis();
        if (retryIndex != index) { retryIndex = index; retryStart = now; }
        int timeout = step.optInt("timeout", 8000);
        if (now - retryStart > timeout) { stopScript("Ekranda gerekli öğe bulunamadı. Adım: " + (index + 1), false); return; }
        scriptHandler.postDelayed(scriptPump, 350L);
    }

    private void resetRetry() { retryIndex = -1; retryStart = 0; }

    private void advanceScript(int index, long delay) {
        scriptState.edit().putInt(SCRIPT_INDEX, index + 1).apply(); resetRetry();
        scriptHandler.removeCallbacks(scriptPump); scriptHandler.postDelayed(scriptPump, delay);
    }

    private void stopScript(String message, boolean success) {
        ensureScriptStores();
        scriptState.edit().putBoolean(SCRIPT_RUNNING, false).putString(SCRIPT_LAST_RESULT, success ? "success" : "error").apply();
        scriptBusy = false; resetRetry(); resetCar(); scriptHandler.removeCallbacks(scriptPump);
        if (message != null && !message.isEmpty()) toast(message);
    }

    private void resetCar() { carPhase = ""; carCandidates = new JSONArray(); carCandidateIndex = 0; carScroll = 0; carAccumulatedText = ""; carCurrentTitle = ""; }

    private JSONArray rows() { try { return new JSONArray(scriptSecure.get(SCRIPT_ROWS, "[]")); } catch (Exception e) { return new JSONArray(); } }
    private int rowCount() { return rows().length(); }

    private AccessibilityNodeInfo findAny(AccessibilityNodeInfo root, JSONArray texts) {
        if (texts == null) return null;
        for (int i = 0; i < texts.length(); i++) { AccessibilityNodeInfo n = findExactOrContains(root, texts.optString(i)); if (n != null) return n; }
        return null;
    }

    private AccessibilityNodeInfo findExactOrContains(AccessibilityNodeInfo root, String wanted) {
        if (wanted == null || wanted.trim().isEmpty()) return null;
        try {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(wanted);
            if (found != null) {
                String nw = SafetyPolicy.normalize(wanted);
                for (AccessibilityNodeInfo n : found) {
                    String nt = SafetyPolicy.normalize(safe(n.getText())); String nd = SafetyPolicy.normalize(safe(n.getContentDescription()));
                    if (nt.equals(nw) || nt.contains(nw) || nd.equals(nw) || nd.contains(nw)) {
                        AccessibilityNodeInfo r = AccessibilityNodeInfo.obtain(n); recycle(found); return r;
                    }
                }
            }
            recycle(found);
        } catch (Exception ignored) {}
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root, JSONArray labels) {
        AccessibilityNodeInfo hinted = findAny(root, labels);
        if (hinted != null) {
            AccessibilityNodeInfo e = editableDescendantOrAncestor(hinted); hinted.recycle(); if (e != null) return e;
        }
        ArrayList<AccessibilityNodeInfo> q = new ArrayList<>(); q.add(AccessibilityNodeInfo.obtain(root));
        for (int i = 0; i < q.size() && i < 900; i++) {
            AccessibilityNodeInfo n = q.get(i);
            if (n.isVisibleToUser() && n.isEditable()) { AccessibilityNodeInfo r = AccessibilityNodeInfo.obtain(n); recycle(q); return r; }
            for (int c = 0; c < n.getChildCount(); c++) { AccessibilityNodeInfo ch = n.getChild(c); if (ch != null) q.add(ch); }
        }
        recycle(q); return null;
    }

    private AccessibilityNodeInfo editableDescendantOrAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(n);
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isEditable()) return cur;
            AccessibilityNodeInfo p = cur.getParent(); cur.recycle(); cur = p;
        }
        if (cur != null) cur.recycle();
        ArrayList<AccessibilityNodeInfo> q = new ArrayList<>(); q.add(AccessibilityNodeInfo.obtain(n));
        for (int i = 0; i < q.size() && i < 120; i++) {
            AccessibilityNodeInfo x = q.get(i); if (x.isEditable()) { AccessibilityNodeInfo r = AccessibilityNodeInfo.obtain(x); recycle(q); return r; }
            for (int c = 0; c < x.getChildCount(); c++) { AccessibilityNodeInfo ch = x.getChild(c); if (ch != null) q.add(ch); }
        }
        recycle(q); return null;
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(n);
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            AccessibilityNodeInfo p = cur.getParent(); cur.recycle(); cur = p;
        }
        if (cur != null) cur.recycle(); return null;
    }

    private boolean hasClickableAncestor(AccessibilityNodeInfo n) { AccessibilityNodeInfo c = clickableAncestor(n); boolean ok = c != null; if (c != null) c.recycle(); return ok; }

    private String clipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE); ClipData c = cm == null ? null : cm.getPrimaryClip();
            if (c == null || c.getItemCount() == 0) return ""; CharSequence s = c.getItemAt(0).coerceToText(this); return s == null ? "" : s.toString();
        } catch (Exception e) { return ""; }
    }

    private ArrayList<Uri> listImages(Uri tree, int max) throws Exception {
        ArrayList<Uri> out = new ArrayList<>(); String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        Cursor c = getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC");
        if (c == null) return out;
        try {
            int idI = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID), mimeI = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE), nameI = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (c.moveToNext() && out.size() < max) {
                String id = c.getString(idI); String mime = mimeI >= 0 ? c.getString(mimeI) : ""; String name = nameI >= 0 ? c.getString(nameI) : "";
                boolean image = mime != null && mime.startsWith("image/");
                if (!image && name != null) { String n = name.toLowerCase(Locale.ROOT); image = n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"); }
                if (image) out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
            }
        } finally { c.close(); }
        return out;
    }

    private String visibleText(AccessibilityNodeInfo root) {
        StringBuilder b = new StringBuilder(); ArrayList<AccessibilityNodeInfo> q = new ArrayList<>(); q.add(AccessibilityNodeInfo.obtain(root));
        for (int i = 0; i < q.size() && i < 1600; i++) {
            AccessibilityNodeInfo n = q.get(i); if (n.isVisibleToUser()) {
                String t = safe(n.getText()).trim(); String d = safe(n.getContentDescription()).trim();
                if (!t.isEmpty()) b.append(t).append('\n'); if (!d.isEmpty() && !d.equals(t)) b.append(d).append('\n');
            }
            for (int c = 0; c < n.getChildCount(); c++) { AccessibilityNodeInfo ch = n.getChild(c); if (ch != null) q.add(ch); }
        }
        recycle(q); return b.toString();
    }

    private String chromeUrl(AccessibilityNodeInfo root) {
        try {
            List<AccessibilityNodeInfo> f = root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar");
            if (f != null && !f.isEmpty()) { String u = safe(f.get(0).getText()); recycle(f); if (!u.startsWith("http")) u = "https://" + u; return u; }
            recycle(f);
        } catch (Exception ignored) {}
        return "";
    }

    private void swipePage(boolean down, long duration) {
        Rect r = new Rect(); getRootInActiveWindowBounds(r); float x = r.width() * .5f;
        float sy = down ? r.height() * .3f : r.height() * .78f; float ey = down ? r.height() * .78f : r.height() * .30f;
        Path p = new Path(); p.moveTo(x, sy); p.lineTo(x, ey);
        dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, duration)).build(), null, null);
    }

    private void getRootInActiveWindowBounds(Rect out) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) { root.getBoundsInScreen(out); root.recycle(); }
        if (out.width() <= 0 || out.height() <= 0) { out.set(0, 0, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels); }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int toInt(String s, int def) { try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return def; } }
    private static String safe(CharSequence s) { return s == null ? "" : s.toString(); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private static void recycle(List<AccessibilityNodeInfo> list) { if (list != null) for (AccessibilityNodeInfo n : list) if (n != null) n.recycle(); }

    // -------- normal modüllerin statik API'si --------
    public static void beginLearning(Context c, String module, String targetPackage) {
        SharedPreferences s = c.getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE);
        s.edit().putBoolean(KEY_RUNNING, false).putBoolean(KEY_LEARNING, true).putString(KEY_LEARNING_MODULE, module)
                .putString(KEY_TARGET_PACKAGE, targetPackage == null ? "" : targetPackage).apply();
        new SecureStore(c).put(CAL_PREFIX + module, "[]");
    }

    public static void finishLearning(Context c) {
        c.getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_LEARNING, false).remove(KEY_LEARNING_MODULE).remove(KEY_TARGET_PACKAGE).apply();
    }

    public static String learningModule(Context c) {
        SharedPreferences s = c.getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE);
        if (!s.getBoolean(KEY_LEARNING, false)) return ""; return s.getString(KEY_LEARNING_MODULE, "");
    }

    public static boolean hasCalibration(Context c, String module) {
        try { return new JSONArray(new SecureStore(c).get(CAL_PREFIX + module, "[]")).length() > 0; }
        catch (Exception e) { return false; }
    }

    public static void beginRun(Context c, String module, String targetPackage, String text, String filesJson) {
        SharedPreferences s = c.getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE);
        s.edit().putBoolean(KEY_LEARNING, false).putBoolean(KEY_RUNNING, true).putString(KEY_RUNNING_MODULE, module)
                .putString(KEY_TARGET_PACKAGE, targetPackage == null ? "" : targetPackage).putInt(KEY_STEP_INDEX, 0).apply();
        SecureStore secure = new SecureStore(c); secure.put(KEY_RUNNING_TEXT, text == null ? "" : text); secure.put(KEY_RUNNING_FILES, filesJson == null ? "[]" : filesJson);
    }
}
