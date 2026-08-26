package com.videofabrikasi.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SecureStore secure;
    private ProjectStore project;
    private SharedPreferences prefs;
    private KaggleClient kaggle;
    private EditText username, token, idea;
    private TextView status, projectInfo;
    private Button generate, refresh, retry, download, prevProject, nextProject;
    private boolean busy = false;

    private final Runnable autoPoll = new Runnable() {
        @Override public void run() {
            if (project != null && project.hasActiveProject()) refreshStatus(false);
            handler.postDelayed(this, 20000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        secure = new SecureStore(this);
        project = new ProjectStore(this);
        prefs = getSharedPreferences("video_factory_settings", MODE_PRIVATE);
        kaggle = new KaggleClient();
        setContentView(buildUi());
        restore();
        handler.postDelayed(autoPoll, 1500);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(autoPoll);
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(36));
        root.setBackgroundColor(Color.rgb(246, 246, 246));
        scroll.addView(root);
        root.addView(label("VIDEO FABRİKASI", 28, true));
        TextView sub = label("Telefon kumanda • Kaggle GPU üretim motoru", 14, false);
        sub.setTextColor(Color.DKGRAY);
        root.addView(sub);

        root.addView(section("1 — Kaggle bağlantısı"));
        username = edit("Kaggle kullanıcı adı");
        username.setId(R.id.username);
        root.addView(username);
        token = edit("Kaggle API token");
        token.setId(R.id.token);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(token);
        LinearLayout auth = row();
        Button save = button("GÜVENLİ KAYDET");
        save.setId(R.id.save_auth);
        Button test = button("BAĞLANTIYI TEST ET");
        test.setId(R.id.test_auth);
        auth.addView(save, weight());
        auth.addView(test, weight());
        root.addView(auth);
        save.setOnClickListener(v -> saveAuth());
        test.setOnClickListener(v -> testConnection());

        root.addView(section("2 — Video fikri"));
        idea = edit("Video fikri / hikâye");
        idea.setId(R.id.idea);
        idea.setMinLines(5);
        idea.setGravity(Gravity.TOP);
        root.addView(idea);
        generate = button("▶  VİDEOYU ÜRET");
        generate.setId(R.id.generate);
        root.addView(generate, full());
        generate.setOnClickListener(v -> startGeneration(false));

        root.addView(section("3 — Üretim durumu"));
        status = label("HAZIR", 20, true);
        status.setId(R.id.status_text);
        status.setPadding(dp(12), dp(14), dp(12), dp(14));
        status.setBackgroundColor(Color.WHITE);
        root.addView(status, full());
        projectInfo = label("Henüz proje yok.", 13, false);
        projectInfo.setId(R.id.project_text);
        root.addView(projectInfo, full());

        LinearLayout nav = row();
        prevProject = button("← ÖNCEKİ PROJE");
        prevProject.setId(R.id.prev_project);
        nextProject = button("SONRAKİ PROJE →");
        nextProject.setId(R.id.next_project);
        nav.addView(prevProject, weight());
        nav.addView(nextProject, weight());
        root.addView(nav);
        prevProject.setOnClickListener(v -> navigateProject(-1));
        nextProject.setOnClickListener(v -> navigateProject(1));

        LinearLayout row1 = row();
        refresh = button("↻ YENİLE");
        refresh.setId(R.id.refresh);
        Button stop = button("■ TAKİBİ DURAKLAT");
        stop.setId(R.id.stop);
        row1.addView(refresh, weight());
        row1.addView(stop, weight());
        root.addView(row1);
        refresh.setOnClickListener(v -> {
            resumeTracking();
            refreshStatus(true);
        });
        stop.setOnClickListener(v -> pauseTracking());

        LinearLayout row2 = row();
        retry = button("↻ TÜMÜNÜ YENİDEN ÜRET");
        retry.setId(R.id.retry);
        download = button("⬇ MP4 İNDİR");
        download.setId(R.id.download);
        row2.addView(retry, weight());
        row2.addView(download, weight());
        root.addView(row2);
        retry.setOnClickListener(v -> startGeneration(true));
        download.setOnClickListener(v -> downloadFinal());

        TextView note = label("Telefon AI hesaplamaz. Birden fazla video Kaggle'a gönderilebilir; son 500 proje telefonda saklanır. MP4 yalnız gerçek AI üretimi doğrulandıktan sonra indirilebilir.", 12, false);
        note.setTextColor(Color.GRAY);
        root.addView(note, full());
        return scroll;
    }

    private void restore() {
        username.setText(prefs.getString("username", ""));
        token.setText(secure.get("kaggle_token"));
        String saved = project.idea();
        if (saved.isEmpty()) {
            saved = "İki aynı beyaz mektup aynı kişiye gidiyor. Biri iyi haber taşıyor ve özgüvenli; diğeri kötü haber taşıyor ve panik içinde. Mutlu mektup posta kutusuna girmek isterken kötü haber mektubu çığlık atarak arkasından yetişip onu kutuya iter. Kişi önce kötü haberi okuyunca çöker ve iyi haberi açmadan yere düşürür.";
        }
        idea.setText(saved);
        renderProject();
    }

    private void navigateProject(int delta) {
        if (busy) return;
        if (!project.move(delta)) {
            toast("Gezinilecek kayıtlı proje yok.");
            return;
        }
        idea.setText(project.idea());
        if (!project.username().isEmpty()) username.setText(project.username());
        renderProject();
    }

    private void saveAuth() {
        String u = username.getText().toString().trim();
        String t = token.getText().toString().trim();
        if (u.isEmpty() || t.isEmpty()) {
            toast("Kullanıcı adı ve token gerekli.");
            return;
        }
        try {
            secure.put("kaggle_token", t);
            prefs.edit().putString("username", u).apply();
            toast("Bilgiler Android Keystore ile güvenli kaydedildi.");
        } catch (Exception e) {
            showError("Güvenli kayıt başarısız", e);
        }
    }

    private void testConnection() {
        if (busy) return;
        String t = token.getText().toString().trim();
        if (t.isEmpty()) {
            toast("Önce API token gir.");
            return;
        }
        setBusy(true, "BAĞLANTI TEST EDİLİYOR…");
        executor.execute(() -> {
            try {
                KaggleClient.Result r = kaggle.validateToken(t);
                if (!r.ok()) throw new IllegalStateException("HTTP " + r.code + " " + r.body);
                ui(() -> {
                    setBusy(false, "BAĞLANTI TAMAM");
                    toast("Kaggle bağlantısı başarılı.");
                });
            } catch (Exception e) {
                ui(() -> {
                    setBusy(false, "BAĞLANTI HATASI");
                    showError("Kaggle bağlantısı", e);
                });
            }
        });
    }

    private void startGeneration(boolean retrying) {
        if (busy) return;
        String u = username.getText().toString().trim();
        String t = token.getText().toString().trim();
        String story = idea.getText().toString().trim();
        if (u.isEmpty() || t.isEmpty()) {
            toast("Önce Kaggle kullanıcı adı ve token gir.");
            return;
        }
        if (story.length() < 20) {
            toast("Hikâye çok kısa.");
            return;
        }
        try {
            secure.put("kaggle_token", t);
            prefs.edit().putString("username", u).apply();
        } catch (Exception e) {
            showError("Token güvenli kaydedilemedi", e);
            return;
        }

        String stamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String base = KaggleClient.slugify(story);
        String slug = "vf-" + base.substring(0, Math.min(base.length(), 22)) + "-" + stamp;
        String title = "Video Fabrikasi " + stamp;
        String script = VideoFactoryScript.build(story, slug);
        project.save(u, slug, title, story, "GÖNDERİLİYOR", 0);
        renderProject();
        setBusy(true, retrying ? "TÜM VİDEO YENİDEN GÖNDERİLİYOR…" : "GPU İŞİ GÖNDERİLİYOR…");

        executor.execute(() -> {
            try {
                KaggleClient.PushResult r = kaggle.pushKernel(u, slug, title, script, t);
                project.save(u, slug, title, story, "KUYRUKTA", r.version);
                ui(() -> {
                    setBusy(false, "KUYRUKTA");
                    renderProject();
                    toast("Kaggle GPU işi oluşturuldu. Yeni bir fikir girip ikinci videoyu da gönderebilirsin.");
                    handler.postDelayed(() -> refreshStatus(false), 5000);
                });
            } catch (Exception e) {
                project.updateStatus("HATALI");
                ui(() -> {
                    setBusy(false, "HATALI");
                    renderProject();
                    showError("Üretim başlatılamadı", e);
                });
            }
        });
    }

    private void refreshStatus(boolean userAction) {
        if (!project.hasActiveProject() || busy) {
            if (userAction && !project.hasActiveProject()) toast("Aktif proje yok.");
            return;
        }
        String activeSlug = project.slug();
        String activeUser = project.username();
        int activeVersion = project.version();
        String t = secure.get("kaggle_token");
        if (t.isEmpty()) {
            if (userAction) toast("Kaggle token bulunamadı.");
            return;
        }
        setBusy(true, "DURUM KONTROL EDİLİYOR…");
        executor.execute(() -> {
            try {
                String remote = kaggle.getStatus(activeUser, activeSlug, t);
                String verified = remote;
                if ("TAMAMLANDI".equals(remote)) {
                    try {
                        verified = kaggle.getOutputState(activeUser, activeSlug, activeVersion, t);
                    } catch (Exception outputError) {
                        verified = "TAMAMLANDI — AI ÇIKTISI DOĞRULANAMADI";
                    }
                }
                final String finalState = verified;
                if (activeSlug.equals(project.slug())) project.updateStatus(finalState);
                ui(() -> {
                    setBusy(false, activeSlug.equals(project.slug()) ? finalState : project.status());
                    renderProject();
                    if (userAction) toast("Durum: " + finalState);
                });
            } catch (Exception e) {
                ui(() -> {
                    setBusy(false, project.status());
                    if (userAction) showError("Durum alınamadı", e);
                });
            }
        });
    }

    private void pauseTracking() {
        handler.removeCallbacks(autoPoll);
        toast("Otomatik telefon takibi durdu; Kaggle işleri etkilenmez. Yenile ile takip tekrar başlar.");
    }

    private void resumeTracking() {
        handler.removeCallbacks(autoPoll);
        handler.postDelayed(autoPoll, 20000);
    }

    private void downloadFinal() {
        if (!project.hasActiveProject()) {
            toast("İndirilecek proje yok.");
            return;
        }
        if (!project.status().startsWith("AI TAMAMLANDI")) {
            toast("MP4 ancak gerçek AI üretimi doğrulandıktan sonra indirilebilir. Önce Yenile.");
            return;
        }
        String t = secure.get("kaggle_token");
        if (t.isEmpty()) {
            toast("Kaggle token bulunamadı.");
            return;
        }
        if (busy) return;
        String activeUser = project.username();
        String activeSlug = project.slug();
        int activeVersion = project.version();
        setBusy(true, "MP4 BAĞLANTISI HAZIRLANIYOR…");
        executor.execute(() -> {
            try {
                KaggleClient.DownloadTarget target = kaggle.resolveOutputDownload(
                        activeUser, activeSlug, activeVersion, "FINAL.mp4", t);
                ui(() -> {
                    try {
                        enqueueDownload(target, t, activeSlug);
                        setBusy(false, project.status());
                        renderProject();
                    } catch (Exception e) {
                        setBusy(false, project.status());
                        showError("İndirme başlatılamadı", e);
                    }
                });
            } catch (Exception e) {
                ui(() -> {
                    setBusy(false, project.status());
                    showError("MP4 bağlantısı alınamadı", e);
                });
            }
        });
    }

    private void enqueueDownload(KaggleClient.DownloadTarget target, String tokenValue, String slug) {
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(target.url));
        if (target.authRequired && !tokenValue.isEmpty()) {
            req.addRequestHeader("Authorization", "Bearer " + tokenValue);
        }
        req.setTitle("Video Fabrikası — " + slug);
        req.setDescription("FINAL.mp4 indiriliyor");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "VideoFabrikasi-" + slug + ".mp4");
        req.setMimeType("video/mp4");
        long id = ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
        prefs.edit().putLong("last_download_id", id).apply();
        toast("MP4 indirme başlatıldı.");
    }

    private void renderProject() {
        if (!project.hasActiveProject()) {
            status.setText("HAZIR");
            projectInfo.setText("Henüz proje yok.");
            return;
        }
        status.setText(project.status());
        int count = project.historyCount();
        int position = project.historyPosition();
        projectInfo.setText("Kayıt: " + position + "/" + count
                + "\nProje: " + project.slug() + "\nKullanıcı: " + project.username()
                + "\nKaggle sürüm: " + project.version()
                + "\nUzak üretim telefon kapansa bile devam eder.");
    }

    private void setBusy(boolean value, String text) {
        busy = value;
        status.setText(text);
        generate.setEnabled(!value);
        refresh.setEnabled(!value);
        retry.setEnabled(!value);
        download.setEnabled(!value);
        if (prevProject != null) prevProject.setEnabled(!value);
        if (nextProject != null) nextProject.setEnabled(!value);
        View v = findViewById(R.id.test_auth);
        if (v != null) v.setEnabled(!value);
    }

    private void showError(String title, Exception e) {
        String m = e == null ? "Bilinmeyen hata" : e.getMessage();
        if (m == null || m.isEmpty()) m = e.toString();
        status.setText(title + "\n" + m);
        toast(title + ": " + m);
    }

    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(16);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams p = full();
        p.bottomMargin = dp(10);
        e.setLayoutParams(p);
        return e;
    }

    private TextView section(String s) {
        TextView t = label(s, 16, true);
        t.setPadding(0, dp(24), 0, dp(10));
        return t;
    }

    private TextView label(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(20, 20, 20));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinHeight(dp(52));
        return b;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(0, dp(6), 0, dp(6));
        return l;
    }

    private LinearLayout.LayoutParams full() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(8);
        return p;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        return p;
    }

    private int dp(int x) {
        return (int) (x * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void ui(Runnable r) {
        runOnUiThread(r);
    }
}
