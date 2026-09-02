package com.videofabrikasi.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
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
import android.widget.VideoView;
import java.util.Map;
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
    private Button generate, refresh, retry, download, prevProject, nextProject, playPause;
    private VideoView player;
    private BroadcastReceiver downloadReceiver;
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
        registerDownloadReceiver();
        executor.execute(this::reconcilePendingDownloads);
        handler.postDelayed(autoPoll, 1500);
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs != null && username != null) {
            String savedUser = prefs.getString("username", "").trim();
            if (!savedUser.isEmpty()) username.setText(savedUser);
        }
        if (secure != null && token != null
                && KaggleSessionPolicy.hasCredential(
                        secure.get("kaggle_token"), secure.get("kaggle_refresh_token"))) {
            token.setText("");
            token.setHint("Kaggle bağlı — OAuth/API kimliği Keystore’da güvenli");
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(autoPoll);
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        }
        if (player != null) {
            try { player.stopPlayback(); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        // Android 15 enforces edge-to-edge for targetSdk 35. Keep every interactive
        // control inside the real system-bar safe area so bottom buttons cannot overlap
        // gesture/taskbar navigation and accidentally send input to the launcher.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = Math.max(0, insets.getSystemWindowInsetTop());
            int bottom = Math.max(0, insets.getSystemWindowInsetBottom());
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        scroll.post(scroll::requestApplyInsets);

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
        TextView connectNote = label("Önerilen: aşağıdaki tek düğmeyi kullan. Kullanıcı adı, token dosyası ve güvenli saklama işlemlerini uygulama yönetecek.", 13, false);
        connectNote.setTextColor(Color.DKGRAY);
        root.addView(connectNote, full());

        Button easyConnect = button("KAGGLE’I KOLAY BAĞLA / CANLI TEST");
        easyConnect.setId(R.id.open_live_e2e);
        root.addView(easyConnect, full());
        easyConnect.setOnClickListener(v -> startActivity(new Intent(this, LiveE2EActivity.class)));

        TextView advancedAuth = label("Gelişmiş / yedek bağlantı alanları", 12, true);
        advancedAuth.setTextColor(Color.GRAY);
        root.addView(advancedAuth, full());
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

        playPause = button("▶ İNDİRİLENİ OYNAT");
        playPause.setId(R.id.play_pause);
        root.addView(playPause, full());
        playPause.setOnClickListener(v -> togglePlayback());

        player = new VideoView(this);
        player.setId(R.id.video_player);
        player.setVisibility(View.GONE);
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420));
        videoParams.bottomMargin = dp(12);
        root.addView(player, videoParams);
        player.setOnCompletionListener(mp -> {
            if (playPause != null) playPause.setText("▶ TEKRAR OYNAT");
        });
        player.setOnErrorListener((mp, what, extra) -> {
            if (playPause != null) playPause.setText("▶ İNDİRİLENİ OYNAT");
            toast("Video oynatılamadı. Dosya yeniden doğrulanmalı.");
            return true;
        });

        TextView note = label("Telefon AI hesaplamaz. Birden fazla video Kaggle'a gönderilebilir; son 500 proje telefonda saklanır. MP4 yalnız gerçek AI üretimi doğrulandıktan sonra indirilebilir.", 12, false);
        note.setTextColor(Color.GRAY);
        root.addView(note, full());
        return scroll;
    }

    private void restore() {
        username.setText(prefs.getString("username", ""));
        token.setText("");
        if (KaggleSessionPolicy.hasCredential(
                secure.get("kaggle_token"), secure.get("kaggle_refresh_token"))) {
            token.setHint("Kaggle bağlı — OAuth/API kimliği Keystore’da güvenli");
        }
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
        resetPlayerForProject();
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
            secure.put("kaggle_refresh_token", "");
            prefs.edit()
                    .putString("username", u)
                    .remove("oauth_access_expires_at")
                    .apply();
            token.setText("");
            token.setHint("Kaggle bağlı — API token Keystore’da güvenli");
            toast("Bilgiler Android Keystore ile güvenli kaydedildi.");
        } catch (Exception e) {
            showError("Güvenli kayıt başarısız", e);
        }
    }

    private void testConnection() {
        if (busy) return;
        final String manualToken = token.getText().toString().trim();
        if (!hasKaggleCredential(manualToken)) {
            toast("Kaggle bağlantısı yok. KAGGLE’I KOLAY BAĞLA düğmesini kullan.");
            return;
        }
        setBusy(true, "BAĞLANTI TEST EDİLİYOR…");
        executor.execute(() -> {
            try {
                String resolvedToken = usableKaggleToken(manualToken);
                KaggleClient.Result r = kaggle.validateToken(resolvedToken);
                if (!r.ok()) throw new IllegalStateException("HTTP " + r.code + " " + r.body);
                ui(() -> {
                    token.setText("");
                    token.setHint("Kaggle bağlı — OAuth/API kimliği Keystore’da güvenli");
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
        if (!liveE2ePassed()) {
            toast("İlk kullanımda gerçek Kaggle T4 canlı sistem testi geçmelidir.");
            startActivity(new Intent(this, LiveE2EActivity.class));
            return;
        }

        String requestedUser = username.getText().toString().trim();
        if (requestedUser.isEmpty()) requestedUser = prefs.getString("username", "").trim();
        final String manualToken = token.getText().toString().trim();
        final String story = idea.getText().toString().trim();

        if (!hasKaggleCredential(manualToken)) {
            toast("Kaggle bağlantısı yok. KAGGLE’I KOLAY BAĞLA düğmesini kullan.");
            return;
        }
        if (story.length() < 20) {
            toast("Hikâye çok kısa.");
            return;
        }

        final String requestedUserFinal = requestedUser;
        String stamp = String.valueOf(System.currentTimeMillis());
        String base = KaggleClient.slugify(story);
        final String slug = "vf-" + base.substring(0, Math.min(base.length(), 20)) + "-" + stamp;
        final String title = slug;
        final String script = VideoFactoryScript.build(story, slug);

        resetPlayerForProject();
        setBusy(true, retrying ? "TÜM VİDEO YENİDEN GÖNDERİLİYOR…" : "GPU İŞİ GÖNDERİLİYOR…");

        executor.execute(() -> {
            try {
                String resolvedToken = usableKaggleToken(manualToken);
                KaggleClient.AccountIdentity identity = kaggle.introspectToken(resolvedToken);
                if (!identity.active || identity.username.isEmpty()) {
                    throw new IllegalStateException("Kaggle oturumu aktif değil.");
                }
                final String resolvedUser = requestedUserFinal.isEmpty()
                        ? identity.username : requestedUserFinal;
                if (!resolvedUser.equalsIgnoreCase(identity.username)) {
                    throw new IllegalStateException(
                            "Kaggle hesabı uyuşmuyor: bağlı hesap " + identity.username);
                }

                prefs.edit().putString("username", resolvedUser).apply();
                project.save(resolvedUser, slug, title, story, "GÖNDERİLİYOR", 0);
                KaggleClient.PushResult r =
                        kaggle.pushKernel(resolvedUser, slug, title, script, resolvedToken);
                project.save(resolvedUser, slug, title, story, "KUYRUKTA", r.version);
                ui(() -> {
                    username.setText(resolvedUser);
                    token.setText("");
                    token.setHint("Kaggle bağlı — OAuth/API kimliği Keystore’da güvenli");
                    setBusy(false, "KUYRUKTA");
                    renderProject();
                    toast("Kaggle GPU işi oluşturuldu. Yeni bir fikir girip ikinci videoyu da gönderebilirsin.");
                    handler.postDelayed(() -> refreshStatus(false), 5000);
                });
            } catch (Exception e) {
                if (project.hasActiveProject() && slug.equals(project.slug())) {
                    project.updateStatus("HATALI");
                }
                ui(() -> {
                    setBusy(false, project.hasActiveProject() ? project.status() : "HATALI");
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
        if (!hasKaggleCredential("")) {
            if (userAction) toast("Kaggle bağlantısı bulunamadı.");
            return;
        }
        setBusy(true, "DURUM KONTROL EDİLİYOR…");
        executor.execute(() -> {
            try {
                String t = usableKaggleToken("");
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
        if (!hasKaggleCredential("")) {
            toast("Kaggle bağlantısı bulunamadı.");
            return;
        }
        if (busy) return;
        String activeUser = project.username();
        String activeSlug = project.slug();
        int activeVersion = project.version();
        setBusy(true, "MP4 BAĞLANTISI HAZIRLANIYOR…");
        executor.execute(() -> {
            try {
                String t = usableKaggleToken("");
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
        prefs.edit().putLong("last_download_id", id).putString("download_slug_" + id, slug).apply();
        toast("MP4 indirme başlatıldı; tamamlanınca dosya doğrulanacak.");
    }

    private void registerDownloadReceiver() {
        downloadReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (id <= 0L) return;
                String slug = prefs.getString("download_slug_" + id, "");
                if (slug == null || slug.isEmpty()) return;
                // DownloadManager sends this broadcast from outside our app process, so the
                // receiver is exported. A spoofed broadcast cannot mark a project successful:
                // the ID must exist in our pending map and verifyDownloadedVideo queries the
                // authoritative DownloadManager row before accepting the file.
                executor.execute(() -> verifyDownloadedVideo(id, slug));
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        // minSdk is 26, and this three-argument overload exists since API 26. Using one
        // explicit exported-state path on every supported Android version also keeps lint
        // and runtime semantics identical.
        registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    private void reconcilePendingDownloads() {
        try {
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("download_slug_")) continue;
                long id;
                try { id = Long.parseLong(key.substring("download_slug_".length())); }
                catch (Exception ignored) { continue; }
                String slug = String.valueOf(entry.getValue());
                int state = downloadState(id);
                if (state == DownloadManager.STATUS_SUCCESSFUL || state == DownloadManager.STATUS_FAILED) {
                    verifyDownloadedVideo(id, slug);
                }
            }
        } catch (Exception ignored) {}
    }

    private int downloadState(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
        try (Cursor c = dm.query(q)) {
            if (c == null || !c.moveToFirst()) return -1;
            return c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        } catch (Exception e) {
            return -1;
        }
    }

    private void verifyDownloadedVideo(long id, String slug) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
        try (Cursor c = dm.query(q)) {
            if (c == null || !c.moveToFirst()) throw new IllegalStateException("İndirme kaydı bulunamadı.");
            int state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (state != DownloadManager.STATUS_SUCCESSFUL) {
                int reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                throw new IllegalStateException("Android indirme başarısız. Kod: " + reason);
            }
            long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            if (total >= 0 && total < 100000) throw new IllegalStateException("İndirilen MP4 beklenmedik derecede küçük: " + total + " bayt");
            String local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            if (local == null || local.isEmpty()) throw new IllegalStateException("İndirilen dosyanın yerel URI bilgisi yok.");

            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(this, Uri.parse(local));
                int width = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int height = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                long duration = parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                if (width != 1080 || height != 1920) {
                    throw new IllegalStateException("İndirilen video 1080x1920 değil: " + width + "x" + height);
                }
                if (duration < 5000L) {
                    throw new IllegalStateException("İndirilen videonun süresi şüpheli: " + duration + " ms");
                }
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }

            project.updateStatusForSlug(slug, "AI TAMAMLANDI — İNDİRİLDİ");
            prefs.edit().remove("download_slug_" + id)
                    .putBoolean("last_download_verified", true)
                    .putString("verified_uri_" + slug, local).apply();
            ui(() -> {
                if (slug.equals(project.slug())) {
                    renderProject();
                    resetPlayerForProject();
                }
                toast("MP4 indirildi ve doğrulandı.");
            });
        } catch (Exception e) {
            project.updateStatusForSlug(slug, "AI TAMAMLANDI — İNDİRME HATASI");
            prefs.edit().remove("download_slug_" + id).putBoolean("last_download_verified", false).apply();
            ui(() -> {
                if (slug.equals(project.slug())) renderProject();
                showError("MP4 indirme doğrulaması", e);
            });
        }
    }

    private void togglePlayback() {
        if (!project.hasActiveProject()) {
            toast("Oynatılacak proje yok.");
            return;
        }
        String local = prefs.getString("verified_uri_" + project.slug(), "");
        if (local == null || local.isEmpty()) {
            toast("Bu proje için doğrulanmış indirilmiş MP4 yok.");
            return;
        }
        if (local.equals(player.getTag())) {
            if (player.isPlaying()) {
                player.pause();
                playPause.setText("▶ DEVAM ET");
            } else {
                player.start();
                playPause.setText("Ⅱ DURAKLAT");
            }
            return;
        }
        player.setTag(local);
        player.setVisibility(View.VISIBLE);
        player.setVideoURI(Uri.parse(local));
        player.setOnPreparedListener(mp -> {
            player.start();
            playPause.setText("Ⅱ DURAKLAT");
        });
    }

    private void resetPlayerForProject() {
        if (player == null || playPause == null) return;
        try { player.stopPlayback(); } catch (Exception ignored) {}
        player.setTag(null);
        String local = project.hasActiveProject()
                ? prefs.getString("verified_uri_" + project.slug(), "") : "";
        if (local == null || local.isEmpty()) {
            player.setVisibility(View.GONE);
            playPause.setText("▶ İNDİRİLENİ OYNAT");
        } else {
            player.setVisibility(View.VISIBLE);
            playPause.setText("▶ İNDİRİLENİ OYNAT");
        }
    }

    private boolean hasKaggleCredential(String manualToken) {
        if (manualToken != null && !manualToken.trim().isEmpty()) return true;
        return KaggleSessionPolicy.hasCredential(
                secure == null ? "" : secure.get("kaggle_token"),
                secure == null ? "" : secure.get("kaggle_refresh_token"));
    }

    private String usableKaggleToken(String manualToken) throws Exception {
        String manual = manualToken == null ? "" : manualToken.trim();
        if (!manual.isEmpty()) {
            secure.put("kaggle_token", manual);
            secure.put("kaggle_refresh_token", "");
            prefs.edit().remove("oauth_access_expires_at").apply();
            return manual;
        }

        String access = secure.get("kaggle_token").trim();
        String refresh = secure.get("kaggle_refresh_token").trim();
        if (!KaggleSessionPolicy.hasCredential(access, refresh)) {
            throw new IllegalStateException("Kaggle bağlantısı bulunamadı.");
        }
        if (refresh.isEmpty()) return access;

        long now = System.currentTimeMillis();
        long expiresAt = prefs.getLong("oauth_access_expires_at", 0L);
        if (!KaggleSessionPolicy.shouldRefresh(access, refresh, expiresAt, now)) {
            return access;
        }

        try {
            KaggleClient.OAuthToken renewed = kaggle.refreshOAuthToken(refresh);
            String renewedRefresh = renewed.refreshToken.isEmpty()
                    ? refresh : renewed.refreshToken;
            KaggleClient.AccountIdentity identity =
                    kaggle.introspectToken(renewed.accessToken);
            if (!identity.active || identity.username.isEmpty()) {
                throw new IllegalStateException("Yenilenen Kaggle OAuth oturumu doğrulanamadı.");
            }

            secure.put("kaggle_token", renewed.accessToken);
            secure.put("kaggle_refresh_token", renewedRefresh);
            long newExpiresAt = renewed.expiresInSeconds > 0
                    ? now + renewed.expiresInSeconds * 1000L
                    : now + 60L * 60L * 1000L;
            prefs.edit()
                    .putLong("oauth_access_expires_at", newExpiresAt)
                    .putString("username", identity.username)
                    .apply();
            return renewed.accessToken;
        } catch (Exception refreshFailure) {
            if (KaggleSessionPolicy.canUseAccessAfterRefreshFailure(
                    access, expiresAt, now)) {
                return access;
            }
            throw refreshFailure;
        }
    }

    private boolean liveE2ePassed() {
        return "PASS".equals(getSharedPreferences("live_e2e_certificate", MODE_PRIVATE)
                .getString("state", "IDLE"));
    }

    private String effectiveToken() {
        String typed = token == null ? "" : token.getText().toString().trim();
        if (!typed.isEmpty()) return typed;
        return secure == null ? "" : secure.get("kaggle_token").trim();
    }

    private int parseInt(String value) {
        try { return Integer.parseInt(value == null ? "0" : value); } catch (Exception e) { return 0; }
    }

    private long parseLong(String value) {
        try { return Long.parseLong(value == null ? "0" : value); } catch (Exception e) { return 0L; }
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
