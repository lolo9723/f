package tr.edu.balikesir.anketrapor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 20. modül için genel, beyaz-listeli görev runtime'ı. */
final class AgentScriptRuntimeV4 {
    static final String PREF = "yerel_agent_state";
    static final String SCRIPT_RUNNING = "script_running_v4";
    static final String SCRIPT_INDEX = "script_index_v4";
    static final String SCRIPT_STEPS = "script_steps_v4";
    static final String SCRIPT_SAVED = "agent_saved_script_v4";
    static final String LAST_RESULT = "script_last_result_v4";
    private static final String OWN_APP = "tr.edu.balikesir.yerelajan";

    private final AccessibilityService service;
    private final SharedPreferences state;
    private final SecureStore secure;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retryIndex = -1;
    private long retryStarted;
    private boolean busy;
    private boolean webRunning;

    AgentScriptRuntimeV4(AccessibilityService service) {
        this.service = service;
        state = service.getSharedPreferences(PREF, AccessibilityService.MODE_PRIVATE);
        secure = new SecureStore(service);
    }

    boolean isRunning() { return state.getBoolean(SCRIPT_RUNNING, false); }

    void onEvent(AccessibilityEvent e) {
        if (!isRunning() || webRunning) return;
        String pkg = safe(e == null ? null : e.getPackageName());
        if (SafetyPolicy.isBlockedPackage(service, pkg)) { stop("Hassas uygulama açıldığı için görev durduruldu.", false); return; }
        handler.removeCallbacks(pump); handler.postDelayed(pump, 120);
    }

    boolean maybeStartFromOwnApp(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) return false;
        if (!OWN_APP.equals(safe(e.getPackageName()))) return false;
        String label = String.valueOf(e.getText()); AccessibilityNodeInfo src = e.getSource();
        if (src != null) { label += " " + safe(src.getText()); src.recycle(); }
        if (!label.contains("Hazırla") && !label.contains("Çalıştır")) return false;
        handler.postDelayed(this::startIfAgentDialog, 240); return true;
    }

    void interrupt() { if (isRunning()) stop("Erişilebilirlik hizmeti kesildi.", false); }
    void destroy() { handler.removeCallbacksAndMessages(null); }

    private void startIfAgentDialog() {
        if (!inAgentDialog()) return;
        String latest = secure.get("last_text", "");
        String saved = secure.get(SCRIPT_SAVED, "");
        String script = looksLikeAnyScript(latest) ? latest : saved;
        if (script == null || script.trim().isEmpty()) { toast("Önce Agent Script seç veya yapıştır."); return; }
        if (looksLikeAnyScript(latest)) secure.put(SCRIPT_SAVED, latest);
        try {
            JSONArray steps; boolean needsClipboard; String name;
            if (AgentScriptEngineV2.looksLikeV2(script)) {
                AgentScriptEngineV2.Plan p = AgentScriptEngineV2.parse(script); steps = p.steps; needsClipboard = p.needsRuntimeClipboard; name = p.name;
            } else {
                AgentScriptEngine.Plan p = AgentScriptEngine.parse(script); steps = upgradeLegacySteps(p.steps); needsClipboard = p.needsRuntimeClipboard; name = p.name;
            }
            if (needsClipboard) {
                String clip = clipboard();
                if (clip.trim().isEmpty() || clip.trim().equals(script.trim())) { toast("Görev kodu kaydedildi. Çalışma metnini panoya kopyalayıp tekrar Çalıştır'a bas."); return; }
            }
            secure.put(SCRIPT_STEPS, steps.toString());
            state.edit().putBoolean(SCRIPT_RUNNING, true).putBoolean("running", false).putBoolean("learning", false)
                    .putInt(SCRIPT_INDEX, 0).putString(LAST_RESULT, "started").apply();
            busy = false; webRunning = false; resetRetry(); toast("Ajan başladı: " + name); handler.post(pump);
        } catch (Exception ex) { toast("Görev kodu hatalı: " + msg(ex)); }
    }

    private JSONArray upgradeLegacySteps(JSONArray source) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject s = source.optJSONObject(i); if (s == null) continue;
            if ("car_search".equals(s.optString("kind"))) {
                JSONObject w = new JSONObject(); w.put("kind", "web_research");
                w.put("spec", AgentScriptEngineV2.carSpec(s.optString("brand", "Chevrolet"), s.optInt("min_year", 2021), s.optInt("max_km", 100000), s.optInt("count", 4), s.optString("filename", "Arac_Ilani.xlsx")));
                out.put(w);
            } else out.put(new JSONObject(s.toString()));
        }
        return out;
    }

    private boolean inAgentDialog() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow(); if (root == null) return false;
        try { List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText("Özel Agent Görevi Çalıştır"); boolean ok = l != null && !l.isEmpty(); recycle(l); return ok; }
        catch (Exception e) { return false; } finally { root.recycle(); }
    }

    private final Runnable pump = this::pumpNow;
    private void pumpNow() {
        if (!isRunning() || busy || webRunning) return;
        JSONArray a = steps(); int index = state.getInt(SCRIPT_INDEX, 0);
        if (index >= a.length()) { stop("Görev tamamlandı.", true); return; }
        JSONObject s = a.optJSONObject(index); if (s == null) { advance(index, 80); return; }
        try {
            switch (s.optString("kind", "")) {
                case "open_app": openApp(s, index); break;
                case "open_url": openUrl(s, index); break;
                case "google_search": googleSearch(s, index); break;
                case "wait": advance(index, clamp(s.optInt("ms", 800), 50, 30000)); break;
                case "tap_any": tapAny(s, index); break;
                case "set_any": setAny(s, index); break;
                case "back": service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); advance(index, 650); break;
                case "swipe": swipe(s, index); break;
                case "share_ajan_album": shareAjanFolder(index); break;
                case "web_research": launchWebResearch(s, index); break;
                case "stop": stop(s.optString("message", "Görev tamamlandı."), true); break;
                default: stop("Desteklenmeyen çalışma adımı: " + s.optString("kind"), false); break;
            }
        } catch (Exception e) { stop("Görev hatası: " + msg(e), false); }
    }

    private void launchWebResearch(JSONObject step, int index) {
        JSONObject spec = step.optJSONObject("spec"); if (spec == null) { stop("Web görev tanımı yok.", false); return; }
        webRunning = true; busy = true;
        ResultReceiver rr = new ResultReceiver(handler) {
            @Override protected void onReceiveResult(int resultCode, Bundle resultData) {
                webRunning = false; busy = false;
                String message = resultData == null ? "" : resultData.getString("message", "");
                if (resultCode == WebResearchActivity.RESULT_FULL) advance(index, 250);
                else if (resultCode == WebResearchActivity.RESULT_PARTIAL && spec.optBoolean("allow_partial", false)) advance(index, 250);
                else stop(message.isEmpty() ? "Web araştırması hedefi tamamlayamadı." : message, false);
            }
        };
        Intent i = new Intent(service, WebResearchActivity.class); i.putExtra(WebResearchActivity.EXTRA_SPEC, spec.toString()); i.putExtra(WebResearchActivity.EXTRA_RECEIVER, rr); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { service.startActivity(i); }
        catch (Exception e) { webRunning = false; busy = false; stop("Web araştırma ekranı açılamadı: " + msg(e), false); }
    }

    private void openApp(JSONObject s, int index) {
        String pkg = s.optString("package", "").trim(); if (pkg.isEmpty()) { stop("OPEN_APP paket adı boş.", false); return; }
        if (SafetyPolicy.isBlockedPackage(service, pkg)) { stop("Hassas uygulama engellendi.", false); return; }
        Intent i = service.getPackageManager().getLaunchIntentForPackage(pkg); if (i == null) { stop("Uygulama bulunamadı: " + pkg, false); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); service.startActivity(i); advance(index, 1100);
    }

    private void openUrl(JSONObject s, int index) {
        String url = s.optString("url", "").trim(); if (!SafetyPolicy.isSafeUrl(url)) { stop("URL güvenlik filtresinden geçmedi.", false); return; }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); i.setPackage("com.android.chrome");
        try { service.startActivity(i); } catch (Exception e) { i.setPackage(null); service.startActivity(i); }
        advance(index, 1400);
    }

    private void googleSearch(JSONObject s, int index) throws Exception {
        JSONObject x = new JSONObject(); x.put("url", "https://www.google.com/search?q=" + URLEncoder.encode(s.optString("query", ""), StandardCharsets.UTF_8.name())); openUrl(x, index);
    }

    private void tapAny(JSONObject s, int index) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow(); if (root == null) { retry(s, index); return; }
        AccessibilityNodeInfo n = null;
        try {
            n = findAny(root, s.optJSONArray("texts")); if (n == null) { retry(s, index); return; }
            if (SafetyPolicy.isProtectedFinal(safe(n.getText())) || SafetyPolicy.isProtectedFinal(safe(n.getContentDescription()))) { stop("Kritik son düğmeye dokunmadım.", true); return; }
            AccessibilityNodeInfo c = clickableAncestor(n); boolean ok = c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK); if (c != null) c.recycle();
            if (ok) { resetRetry(); advance(index, 650); } else retry(s, index);
        } finally { if (n != null) n.recycle(); root.recycle(); }
    }

    private void setAny(JSONObject s, int index) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow(); if (root == null) { retry(s, index); return; }
        AccessibilityNodeInfo n = null;
        try {
            n = findEditable(root, s.optJSONArray("texts")); if (n == null) { retry(s, index); return; }
            String value = "clipboard".equals(s.optString("value_source")) ? clipboard() : s.optString("value", "");
            if (value.isEmpty() && "clipboard".equals(s.optString("value_source"))) { stop("Pano boş.", false); return; }
            Bundle b = new Bundle(); b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) { resetRetry(); advance(index, 450); } else retry(s, index);
        } finally { if (n != null) n.recycle(); root.recycle(); }
    }

    private void swipe(JSONObject s, int index) {
        Rect r = screenRect(); boolean down = "down".equalsIgnoreCase(s.optString("direction", "up")); float x = r.width() * .5f;
        float sy = down ? r.height()*.30f : r.height()*.78f, ey = down ? r.height()*.78f : r.height()*.30f;
        Path p = new Path(); p.moveTo(x, sy); p.lineTo(x, ey); busy = true;
        service.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,400)).build(), new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { busy = false; advance(index, 600); }
            @Override public void onCancelled(GestureDescription g) { busy = false; retry(s, index); }
        }, null);
    }

    private void shareAjanFolder(int index) {
        String tree = service.getSharedPreferences(FolderGrantActivity.PREF, AccessibilityService.MODE_PRIVATE).getString(FolderGrantActivity.KEY_URI, "");
        if (tree.isEmpty()) {
            if (FolderGrantActivity.isActive()) return;
            busy = true; FolderGrantActivity.setCompletionCallback(() -> { busy = false; handler.postDelayed(pump, 250); });
            Intent g = new Intent(service, FolderGrantActivity.class); g.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); service.startActivity(g); toast("Bir kez Ajan klasörünü seç."); return;
        }
        try {
            ArrayList<Uri> imgs = AgentScriptRuntimeV4Files.listImages(service, Uri.parse(tree), 20);
            if (imgs.isEmpty()) { stop("Ajan klasöründe görsel yok.", false); return; }
            Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE); share.setType("image/*"); share.setPackage("com.instagram.android"); share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, imgs); share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            for (Uri u:imgs) service.grantUriPermission("com.instagram.android",u,Intent.FLAG_GRANT_READ_URI_PERMISSION); service.startActivity(share); advance(index, 1700);
        } catch (Exception e) { stop("Ajan klasörü okunamadı: " + msg(e), false); }
    }

    private JSONArray steps() { try { return new JSONArray(secure.get(SCRIPT_STEPS, "[]")); } catch (Exception e) { return new JSONArray(); } }
    private void advance(int index, long delay) { state.edit().putInt(SCRIPT_INDEX,index+1).apply(); resetRetry(); handler.removeCallbacks(pump); handler.postDelayed(pump,delay); }
    private void retry(JSONObject s, int index) { long now=SystemClock.uptimeMillis(); if(retryIndex!=index){retryIndex=index;retryStarted=now;} if(now-retryStarted>clamp(s.optInt("timeout",10000),500,30000)){stop("Gerekli ekran öğesi bulunamadı. Adım: "+(index+1),false);return;} handler.postDelayed(pump,320); }
    private void resetRetry(){retryIndex=-1;retryStarted=0;}
    private void stop(String m, boolean ok){state.edit().putBoolean(SCRIPT_RUNNING,false).putString(LAST_RESULT,ok?"success":"error").apply();busy=false;webRunning=false;resetRetry();handler.removeCallbacks(pump);toast(m);}

    private AccessibilityNodeInfo findAny(AccessibilityNodeInfo root, JSONArray a){if(a==null)return null;for(int i=0;i<a.length();i++){AccessibilityNodeInfo n=findText(root,a.optString(i));if(n!=null)return n;}return null;}
    private AccessibilityNodeInfo findText(AccessibilityNodeInfo root,String w){if(w==null||w.trim().isEmpty())return null;try{List<AccessibilityNodeInfo> l=root.findAccessibilityNodeInfosByText(w);String nw=SafetyPolicy.normalize(w);if(l!=null)for(AccessibilityNodeInfo n:l){String t=SafetyPolicy.normalize(safe(n.getText())),d=SafetyPolicy.normalize(safe(n.getContentDescription()));if(t.contains(nw)||d.contains(nw)){AccessibilityNodeInfo r=AccessibilityNodeInfo.obtain(n);recycle(l);return r;}}recycle(l);}catch(Exception ignored){}return null;}
    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root,JSONArray labels){AccessibilityNodeInfo h=findAny(root,labels);if(h!=null){AccessibilityNodeInfo e=editableNear(h);h.recycle();if(e!=null)return e;}ArrayList<AccessibilityNodeInfo> q=new ArrayList<>();q.add(AccessibilityNodeInfo.obtain(root));for(int i=0;i<q.size()&&i<900;i++){AccessibilityNodeInfo n=q.get(i);if(n.isVisibleToUser()&&(n.isEditable()||safe(n.getClassName()).toLowerCase(Locale.ROOT).contains("edittext"))){AccessibilityNodeInfo r=AccessibilityNodeInfo.obtain(n);recycle(q);return r;}for(int c=0;c<n.getChildCount();c++){AccessibilityNodeInfo ch=n.getChild(c);if(ch!=null)q.add(ch);}}recycle(q);return null;}
    private AccessibilityNodeInfo editableNear(AccessibilityNodeInfo n){AccessibilityNodeInfo cur=AccessibilityNodeInfo.obtain(n);for(int up=0;up<4&&cur!=null;up++){if(cur.isEditable())return cur;for(int i=0;i<cur.getChildCount();i++){AccessibilityNodeInfo ch=cur.getChild(i);if(ch!=null){if(ch.isEditable()||safe(ch.getClassName()).toLowerCase(Locale.ROOT).contains("edittext")){cur.recycle();return ch;}ch.recycle();}}AccessibilityNodeInfo p=cur.getParent();cur.recycle();cur=p;}if(cur!=null)cur.recycle();return null;}
    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n){AccessibilityNodeInfo cur=AccessibilityNodeInfo.obtain(n);for(int i=0;i<7&&cur!=null;i++){if(cur.isClickable())return cur;AccessibilityNodeInfo p=cur.getParent();cur.recycle();cur=p;}if(cur!=null)cur.recycle();return null;}
    private Rect screenRect(){Rect r=new Rect();AccessibilityNodeInfo root=service.getRootInActiveWindow();if(root!=null){root.getBoundsInScreen(r);root.recycle();}if(r.width()<=0||r.height()<=0)r.set(0,0,service.getResources().getDisplayMetrics().widthPixels,service.getResources().getDisplayMetrics().heightPixels);return r;}
    private String clipboard(){ClipboardManager cm=(ClipboardManager)service.getSystemService(AccessibilityService.CLIPBOARD_SERVICE);ClipData c=cm==null?null:cm.getPrimaryClip();if(c==null||c.getItemCount()==0)return"";CharSequence s=c.getItemAt(0).coerceToText(service);return s==null?"":s.toString();}
    private boolean looksLikeAnyScript(String s){return AgentScriptEngineV2.looksLikeV2(s)||AgentScriptEngine.looksLikeScript(s);}
    private static void recycle(List<AccessibilityNodeInfo> l){if(l!=null)for(AccessibilityNodeInfo n:l)if(n!=null)n.recycle();}
    private static String safe(CharSequence s){return s==null?"":s.toString();}
    private static int clamp(int v,int a,int b){return Math.max(a,Math.min(b,v));}
    private static String msg(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private void toast(String s){Toast.makeText(service,s,Toast.LENGTH_LONG).show();}
}
