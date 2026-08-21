package tr.edu.balikesir.anketrapor;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Yalnız :web prosesinde çalışan görünür, genel web araştırma motoru. */
public class WebResearchActivity extends Activity {
    public static final String EXTRA_SPEC = "web_spec";
    public static final String EXTRA_RECEIVER = "web_receiver";
    public static final int RESULT_ERROR = -1;
    public static final int RESULT_PARTIAL = 0;
    public static final int RESULT_FULL = 1;

    private WebView web;
    private TextView status;
    private TextView counter;
    private ProgressBar progress;
    private Button openFile;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ResultReceiver receiver;
    private boolean resultSent;

    private JSONObject spec;
    private JSONArray queries;
    private JSONArray fields;
    private JSONArray allowedDomains;
    private JSONArray linkContains;
    private JSONArray mustContain;
    private JSONArray mustNotContain;
    private final ArrayList<String> candidates = new ArrayList<>();
    private final Set<String> seenCandidates = new HashSet<>();
    private final Set<String> seenDetails = new HashSet<>();
    private final List<String[]> rows = new ArrayList<>();

    private int queryIndex;
    private int candidateIndex;
    private int pagesVisited;
    private int targetCount;
    private int maxPages;
    private String filename;
    private String state = "idle";
    private boolean pageHandled;
    private boolean usingFallback;
    private boolean finished;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        receiver = getIntent().getParcelableExtra(EXTRA_RECEIVER);
        try {
            String raw = getIntent().getStringExtra(EXTRA_SPEC);
            if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("Web görev tanımı yok.");
            spec = new JSONObject(raw);
            prepareSpec();
            buildUi();
            startResearch();
        } catch (Exception e) {
            buildUi();
            finishWithError("Görev başlatılamadı: " + message(e));
        }
    }

    private void prepareSpec() throws Exception {
        queries = spec.optJSONArray("queries");
        if (queries == null || queries.length() == 0) throw new IllegalArgumentException("Arama sorgusu yok.");
        fields = orEmpty(spec.optJSONArray("fields"));
        allowedDomains = orEmpty(spec.optJSONArray("allowed_domains"));
        linkContains = orEmpty(spec.optJSONArray("link_contains"));
        mustContain = orEmpty(spec.optJSONArray("must_contain"));
        mustNotContain = orEmpty(spec.optJSONArray("must_not_contain"));
        targetCount = clamp(spec.optInt("target_count", 10), 1, 50);
        maxPages = clamp(spec.optInt("max_pages", 40), 1, 120);
        filename = spec.optString("filename", "Yerel_Ajan_Web_Sonuc.xlsx");
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) filename += ".xlsx";
    }

    private void buildUi() {
        if (status != null) return;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.WHITE);
        LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.VERTICAL); head.setPadding(dp(16), dp(14), dp(16), dp(10));
        TextView title = tv("Yerel Ajan • Web Araştırma", 20, true); head.addView(title);
        status = tv("Hazırlanıyor…", 14, false); status.setPadding(0, dp(6), 0, 0); head.addView(status);
        counter = tv("0 / 0", 13, true); counter.setPadding(0, dp(4), 0, 0); head.addView(counter);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setProgress(0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)); p.topMargin = dp(8); head.addView(progress, p);
        openFile = new Button(this); openFile.setText("Excel'i Aç"); openFile.setAllCaps(false); openFile.setVisibility(View.GONE); openFile.setOnClickListener(v -> openOutputFile());
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)); op.topMargin = dp(8); head.addView(openFile, op);
        root.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this); web.setBackgroundColor(Color.WHITE);
        WebSettings ws = web.getSettings(); ws.setJavaScriptEnabled(true); ws.setDomStorageEnabled(true); ws.setLoadsImagesAutomatically(true); ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); ws.setSavePassword(false); ws.setAllowFileAccess(false); ws.setAllowContentAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request == null ? null : request.getUrl();
                if (u == null || !isSafePublicUrl(u.toString(), false)) return true;
                return false;
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (finished || pageHandled) return;
                pageHandled = true;
                handler.postDelayed(() -> handleLoadedPage(url), 900L);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void startResearch() {
        queryIndex = 0; candidateIndex = 0; pagesVisited = 0; rows.clear(); candidates.clear(); seenCandidates.clear(); seenDetails.clear();
        usingFallback = false; finished = false; updateCounter(); loadSearch();
    }

    private void loadSearch() {
        if (finished) return;
        if (rows.size() >= targetCount) { finishSuccess(); return; }
        if (pagesVisited >= maxPages) { finishPartial("Maksimum sayfa sınırına ulaşıldı."); return; }
        if (queryIndex >= queries.length()) {
            if (!usingFallback && spec.optBoolean("allow_search_fallback", true)) { usingFallback = true; queryIndex = 0; }
            else { finishPartial("Arama sorguları tamamlandı."); return; }
        }
        String q = queries.optString(queryIndex, "").trim(); queryIndex++;
        if (q.isEmpty()) { loadSearch(); return; }
        candidates.clear(); candidateIndex = 0;
        String url;
        if (!usingFallback) {
            url = "https://www.google.com/search?num=20&filter=0&q=" + Uri.encode(q);
            setStatus("Google'da aranıyor: " + shortText(q, 62));
        } else {
            url = "https://www.bing.com/search?count=30&q=" + Uri.encode(q);
            setStatus("Google sonuçları yetersizdi • yedek arama: " + shortText(q, 54));
        }
        state = "search"; load(url);
    }

    private void loadDetail(String url) {
        if (finished) return;
        if (rows.size() >= targetCount) { finishSuccess(); return; }
        if (pagesVisited >= maxPages) { finishPartial("Maksimum sayfa sınırına ulaşıldı."); return; }
        if (!isSafePublicUrl(url, true)) { nextCandidate(); return; }
        setStatus("Sayfa doğrulanıyor • " + (rows.size() + 1) + "/" + targetCount + " sonuç aranıyor");
        state = "detail"; load(url);
    }

    private void load(String url) {
        if (finished) return;
        pageHandled = false; pagesVisited++; updateProgress();
        web.loadUrl(url);
        final int stamp = pagesVisited;
        handler.postDelayed(() -> {
            if (!finished && stamp == pagesVisited && !pageHandled) {
                pageHandled = true;
                if ("search".equals(state)) loadSearch(); else nextCandidate();
            }
        }, 15000L);
    }

    private void handleLoadedPage(String url) {
        if (finished) return;
        if ("search".equals(state)) extractSearchLinks();
        else if ("detail".equals(state)) extractDetail();
    }

    private void extractSearchLinks() {
        String js = "(function(){var r=[];var a=document.querySelectorAll('a');for(var i=0;i<a.length;i++){var h=a[i].href||'';var t=(a[i].innerText||a[i].textContent||'').trim();if(!h)continue;try{var u=new URL(h,location.href);if((u.hostname.indexOf('google.')>=0||u.hostname==='www.google.com')&&u.pathname==='/url'){var q=u.searchParams.get('q');if(q)h=q;}else h=u.href;}catch(e){}r.push({h:h,t:t});}return JSON.stringify(r);})()";
        web.evaluateJavascript(js, value -> {
            try {
                String decoded = decodeJsString(value); JSONArray a = new JSONArray(decoded);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject x = a.optJSONObject(i); if (x == null) continue;
                    String h = x.optString("h", "");
                    if (isCandidateUrl(h) && seenCandidates.add(canonical(h))) candidates.add(h);
                }
            } catch (Exception ignored) {}
            if (candidates.isEmpty()) { loadSearch(); return; }
            candidateIndex = 0; nextCandidate();
        });
    }

    private void nextCandidate() {
        if (finished) return;
        while (candidateIndex < candidates.size()) {
            String u = candidates.get(candidateIndex++); String c = canonical(u);
            if (!seenDetails.add(c)) continue;
            loadDetail(u); return;
        }
        loadSearch();
    }

    private void extractDetail() {
        String js = "(function(){var o={url:location.href,title:document.title||'',text:document.body?document.body.innerText:''};return JSON.stringify(o);})()";
        web.evaluateJavascript(js, value -> {
            try {
                String decoded = decodeJsString(value); JSONObject p = new JSONObject(decoded);
                evaluatePage(p.optString("url", web.getUrl()), p.optString("title", ""), p.optString("text", ""));
            } catch (Exception ignored) {}
            if (rows.size() >= targetCount) finishSuccess(); else nextCandidate();
        });
    }

    private void evaluatePage(String url, String title, String text) {
        if (!isCandidateUrl(url)) return;
        if (text == null || text.trim().length() < 80) return;
        String normalized = norm(title + "\n" + text);
        for (int i = 0; i < mustContain.length(); i++) if (!normalized.contains(norm(mustContain.optString(i)))) return;
        String[] defaultBad = {"ilan yayında değil", "ilan yayinda degil", "ilan bulunamadı", "ilan bulunamadi", "satıldı", "satildi", "kaldırıldı", "kaldirildi"};
        for (String b : defaultBad) if (normalized.contains(norm(b))) return;
        for (int i = 0; i < mustNotContain.length(); i++) if (normalized.contains(norm(mustNotContain.optString(i)))) return;

        String[] row = new String[fields.length() + 2]; row[0] = cleanTitle(title);
        for (int i = 0; i < fields.length(); i++) {
            JSONObject f = fields.optJSONObject(i); if (f == null) return;
            String v = extractField(text + "\n" + title, f); if (v == null) return;
            row[i + 1] = v;
        }
        row[row.length - 1] = canonical(url);
        for (String[] r : rows) if (r[r.length - 1].equals(row[row.length - 1])) return;
        rows.add(row); setStatus("Uygun sonuç bulundu: " + rows.size() + "/" + targetCount); updateCounter();
    }

    private String extractField(String text, JSONObject f) {
        JSONArray rx = f.optJSONArray("regex"); if (rx == null) return null;
        String raw = null;
        for (int i = 0; i < rx.length() && raw == null; i++) {
            try {
                Matcher m = Pattern.compile(rx.optString(i), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE).matcher(text);
                if (m.find()) raw = m.groupCount() >= 1 ? m.group(1) : m.group();
            } catch (Exception ignored) {}
        }
        if (raw == null || raw.trim().isEmpty()) return null;
        String type = f.optString("type", "text");
        if ("int".equalsIgnoreCase(type) || "number".equalsIgnoreCase(type)) {
            String digits = raw.replaceAll("[^0-9-]", ""); if (digits.isEmpty()) return null;
            long n; try { n = Long.parseLong(digits); } catch (Exception e) { return null; }
            if (f.has("min") && n < f.optDouble("min")) return null;
            if (f.has("max_exclusive") && n >= f.optDouble("max_exclusive")) return null;
            if (f.has("max") && n > f.optDouble("max")) return null;
            return String.valueOf(n);
        }
        return raw.trim().replaceAll("\\s+", " ");
    }

    private boolean isCandidateUrl(String url) {
        if (!isSafePublicUrl(url, true)) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (linkContains.length() > 0) {
            boolean ok = false; for (int i = 0; i < linkContains.length(); i++) if (lower.contains(linkContains.optString(i).toLowerCase(Locale.ROOT))) { ok = true; break; }
            if (!ok) return false;
        }
        return true;
    }

    private boolean isSafePublicUrl(String url, boolean requireAllowedDomain) {
        try {
            Uri u = Uri.parse(url); String scheme = u.getScheme(), host = u.getHost();
            if (scheme == null || host == null || !"https".equalsIgnoreCase(scheme)) return false;
            String h = host.toLowerCase(Locale.ROOT);
            if (h.equals("localhost") || h.endsWith(".local") || h.equals("127.0.0.1") || h.equals("0.0.0.0") || h.equals("::1")) return false;
            if (h.matches("10\\..*") || h.matches("192\\.168\\..*") || h.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*")) return false;
            String nh = norm(h);
            String[] blocked = {"bank", "banka", "garanti", "akbank", "ziraat", "isbank", "yapikredi", "qnb", "halkbank", "vakifbank", "paypal", "stripe", "auth", "password"};
            for (String b : blocked) if (nh.contains(norm(b))) return false;
            if (!requireAllowedDomain || allowedDomains.length() == 0) return true;
            for (int i = 0; i < allowedDomains.length(); i++) {
                String d = allowedDomains.optString(i, "").toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
                String hh = h.replaceFirst("^www\\.", ""); if (hh.equals(d) || hh.endsWith("." + d)) return true;
            }
            return false;
        } catch (Exception e) { return false; }
    }

    private void finishSuccess() { finishToExcel(true, "Hedef tamamlandı: " + rows.size() + "/" + targetCount + " sonuç doğrulandı."); }
    private void finishPartial(String reason) { finishToExcel(false, reason + " Doğrulanan sonuç: " + rows.size() + "/" + targetCount + "."); }

    private void finishToExcel(boolean full, String message) {
        if (finished) return; finished = true; handler.removeCallbacksAndMessages(null);
        try {
            String[] headers = new String[fields.length() + 2]; headers[0] = "Başlık";
            for (int i = 0; i < fields.length(); i++) headers[i + 1] = fields.optJSONObject(i).optString("name", "Alan" + (i + 1));
            headers[headers.length - 1] = "Link";
            String path = SimpleXlsxWriter.write(this, filename, headers, rows, headers.length - 1);
            String finalMessage = (full ? "✓ " : "⚠ ") + message + "\nExcel: " + path;
            state = "done"; setStatus(finalMessage); updateCounter(); progress.setProgress(100); openFile.setVisibility(View.VISIBLE);
            sendResult(full ? RESULT_FULL : RESULT_PARTIAL, finalMessage);
        } catch (Exception e) { finishWithError("Excel oluşturulamadı: " + message(e)); }
    }

    private void finishWithError(String msg) {
        finished = true; handler.removeCallbacksAndMessages(null); if (status != null) status.setText("✕ " + msg); if (progress != null) progress.setProgress(100); if (counter != null) counter.setText("Görev durdu");
        sendResult(RESULT_ERROR, msg);
    }

    private void sendResult(int code, String message) {
        if (resultSent || receiver == null) return; resultSent = true;
        Bundle b = new Bundle(); b.putString("message", message); b.putString("filename", filename); b.putInt("found", rows.size()); b.putInt("target", targetCount);
        try { receiver.send(code, b); } catch (Exception ignored) {}
    }

    private void openOutputFile() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = getContentResolver(); String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME}; String sel = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
                try (Cursor c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel, new String[]{filename}, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                    if (c != null && c.moveToFirst()) {
                        long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)); Uri u = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(id));
                        Intent i = new Intent(Intent.ACTION_VIEW); i.setDataAndType(u, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(i); return;
                    }
                }
            }
            setStatus("Excel hazır: İndirilenler/Yerel Ajan/" + filename);
        } catch (Exception e) { setStatus("Excel hazır: İndirilenler/Yerel Ajan/" + filename); }
    }

    private void updateCounter() { if (counter != null) counter.setText("Doğrulanan: " + rows.size() + "/" + targetCount + " • Açılan sayfa: " + pagesVisited + "/" + maxPages); }
    private void updateProgress() { updateCounter(); if (progress != null) progress.setProgress(Math.min(95, (int)Math.round((pagesVisited * 100.0) / Math.max(1, maxPages)))); }
    private void setStatus(String s) { if (status != null) status.setText(s); }
    private static JSONArray orEmpty(JSONArray a) { return a == null ? new JSONArray() : a; }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private String decodeJsString(String value) throws Exception { Object x = new JSONTokener(value == null ? "\"\"" : value).nextValue(); return x instanceof String ? (String)x : String.valueOf(x); }
    private String canonical(String u) { try { Uri x = Uri.parse(u); return new Uri.Builder().scheme("https").authority(x.getHost()).path(x.getPath()).encodedQuery(x.getEncodedQuery()).build().toString(); } catch (Exception e) { return u; } }
    private String cleanTitle(String t) { if (t == null) return ""; return t.replaceAll("\\s+", " ").replaceAll("\\s*[-|]\\s*(arabam\\.com|sahibinden\\.com).*$", "").trim(); }
    private String norm(String s) { return SafetyPolicy.normalize(s == null ? "" : s); }
    private String shortText(String s, int n) { if (s == null) return ""; return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }
    private String message(Exception e) { return e == null ? "bilinmeyen hata" : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
    private TextView tv(String s, float sp, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.rgb(30,33,38)); if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); t.setGravity(Gravity.START); return t; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() { if (!finished && web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); if (web != null) { web.stopLoading(); web.destroy(); } super.onDestroy(); }
}
