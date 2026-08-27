package com.videofabrikasi.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Temporary live certification harness. It intentionally uses a fixed Turkish story so the
 * real test exercises V4 Turkish->English prompt preparation plus semantic QC, Kaggle T4 generation, five-scene
 * continuity, status.json proof, signed output download and Android media validation.
 */
public final class LiveE2EActivity extends Activity {
    private static final String PREFS = "live_e2e_certificate";
    private static final String IDLE = "IDLE";
    private static final String RUNNING = "RUNNING";
    private static final String DOWNLOADING = "DOWNLOADING";
    private static final String PASS = "PASS";
    private static final String FAIL = "FAIL";
    private static final long POLL_MS = 20_000L;
    private static final long MAX_RUN_MS = 4L * 60L * 60L * 1000L;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final int REQUEST_TOKEN_FILE = 4107;
    private static final String KAGGLE_API_SETTINGS = "https://www.kaggle.com/settings/api";

    private static final String CANONICAL_STORY =
            "İki beyaz mektup aynı kişiye gidiyor. Biri iyi haber taşıyor ve özgüvenli, "
                    + "diğeri kötü haber taşıyor ve panik içinde. Mutlu mektup posta kutusuna "
                    + "girmek isterken kötü haber mektubu çığlık atarak arkasından yetişip onu "
                    + "kutuya iter. Kişi önce kötü haberi okuyunca çöker ve iyi haberi açmadan "
                    + "yere düşürür. Gizli nitelik davranıştan finalden önce sezilmelidir.";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final KaggleClient kaggle = new KaggleClient();
    private SecureStore secure;
    private SharedPreferences prefs;
    private EditText username;
    private EditText token;
    private TextView status;
    private TextView details;
    private Button start;
    private Button connectKaggle;
    private Button importTokenFile;
    private BroadcastReceiver downloadReceiver;
    private boolean workInFlight;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            pollOnce();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        secure = new SecureStore(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        restore();
        registerDownloadReceiver();
        executor.execute(this::reconcileDownload);
        handler.postDelayed(poll, 2_000L);
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs != null && prefs.getBoolean("waiting_for_kaggle_token", false)
                && !workInFlight && !RUNNING.equals(state()) && !DOWNLOADING.equals(state())) {
            handler.postDelayed(() -> tryImportClipboard(true), 450L);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TOKEN_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Token dosyası açılamadı.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > 65536) throw new IllegalStateException("Token dosyası beklenenden büyük.");
                out.write(buffer, 0, read);
            }
            importCredentialText(out.toString("UTF-8"), true);
        } catch (Exception e) {
            fail("Token dosyası okunamadı: " + safe(e));
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(poll);
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
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

        TextView title = text("VF CANLI E2E SERTİFİKA", 25, true);
        root.addView(title, full());
        TextView note = text(
                "Kolay bağlantı: KAGGLE’I BAĞLA düğmesine bas. Kaggle açılınca hesabına giriş yap, API bölümünde Generate New Token oluştur ve tokenı kopyala. Uygulamaya döndüğünde kullanıcı adı/token otomatik tanınır ve gerçek T4 testi kendiliğinden başlar. JSON, GitHub Secret veya teknik ayar gerekmez.",
                13, false);
        note.setTextColor(Color.DKGRAY);
        root.addView(note, full());

        connectKaggle = button("KAGGLE’I BAĞLA — KOLAY KURULUM");
        connectKaggle.setId(R.id.e2e_connect_kaggle);
        root.addView(connectKaggle, full());
        connectKaggle.setOnClickListener(v -> openKaggleSetup());

        importTokenFile = button("İNDİRİLEN TOKEN DOSYASINI SEÇ");
        importTokenFile.setId(R.id.e2e_import_token_file);
        root.addView(importTokenFile, full());
        importTokenFile.setOnClickListener(v -> pickTokenFile());

        Button clipboard = button("PANODAKİ TOKENI OTOMATİK AL");
        clipboard.setId(R.id.e2e_import_clipboard);
        root.addView(clipboard, full());
        clipboard.setOnClickListener(v -> tryImportClipboard(false));

        TextView advanced = text("Gelişmiş / yedek alanlar (normalde doldurman gerekmez):", 12, true);
        advanced.setTextColor(Color.GRAY);
        root.addView(advanced, full());

        username = edit("Kaggle kullanıcı adı");
        username.setId(R.id.e2e_username);
        root.addView(username, full());
        token = edit("Kaggle API token");
        token.setId(R.id.e2e_token);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(token, full());

        start = button("CANLI SİSTEM TESTİNİ BAŞLAT");
        start.setId(R.id.e2e_start);
        root.addView(start, full());
        start.setOnClickListener(v -> startLiveTest());

        Button main = button("ANA UYGULAMAYI AÇ");
        main.setId(R.id.e2e_open_main);
        root.addView(main, full());
        main.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));

        status = text("CANLI TEST HENÜZ YAPILMADI", 20, true);
        status.setId(R.id.e2e_status);
        status.setBackgroundColor(Color.WHITE);
        status.setPadding(dp(12), dp(14), dp(12), dp(14));
        root.addView(status, full());

        details = text("", 13, false);
        details.setId(R.id.e2e_details);
        details.setGravity(Gravity.START);
        root.addView(details, full());

        TextView story = text("Sabit test hikâyesi:\n" + CANONICAL_STORY, 12, false);
        story.setTextColor(Color.GRAY);
        root.addView(story, full());
        return scroll;
    }

    private void restore() {
        SharedPreferences mainPrefs = getSharedPreferences("video_factory_settings", MODE_PRIVATE);
        username.setText(prefs.getString("username", mainPrefs.getString("username", "")));
        token.setText(secure.get("kaggle_token"));
        render();
    }

    private void openKaggleSetup() {
        prefs.edit().putBoolean("waiting_for_kaggle_token", true).apply();
        status.setText("KAGGLE BAĞLANTISI BEKLENİYOR…");
        details.setText("Kaggle açılıyor. Giriş yap → API → Generate New Token → tokenı kopyala → uygulamaya geri dön. Gerisini uygulama yapacak.");
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(KAGGLE_API_SETTINGS));
            startActivity(i);
        } catch (Exception e) {
            fail("Kaggle token sayfası açılamadı: " + safe(e));
        }
    }

    private void pickTokenFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQUEST_TOKEN_FILE);
        } catch (Exception e) {
            fail("Token dosya seçicisi açılamadı: " + safe(e));
        }
    }

    private void tryImportClipboard(boolean quiet) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                if (!quiet) toast("Panoda Kaggle token bulunamadı.");
                return;
            }
            CharSequence value = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            String raw = value == null ? "" : value.toString();
            String parsed = KaggleClient.tokenFromImportedText(raw);
            if (parsed.isEmpty()) {
                if (!quiet) toast("Panoda KGAT_ ile başlayan Kaggle token bulunamadı.");
                return;
            }
            importCredentialText(parsed, true);
        } catch (Exception e) {
            if (!quiet) fail("Panodaki token okunamadı: " + safe(e));
        }
    }

    private void importCredentialText(String raw, boolean autoStart) {
        if (workInFlight) return;
        final String parsed;
        try {
            parsed = KaggleClient.tokenFromImportedText(raw);
        } catch (Exception e) {
            fail("Kaggle token biçimi okunamadı: " + safe(e));
            return;
        }
        if (parsed.isEmpty()) {
            fail("Geçerli Kaggle API token bulunamadı. Kaggle API sayfasında Generate New Token kullan.");
            return;
        }
        workInFlight = true;
        start.setEnabled(false);
        if (connectKaggle != null) connectKaggle.setEnabled(false);
        if (importTokenFile != null) importTokenFile.setEnabled(false);
        status.setText("KAGGLE HESABI OTOMATİK DOĞRULANIYOR…");
        executor.execute(() -> {
            try {
                KaggleClient.AccountIdentity identity = kaggle.introspectToken(parsed);
                if (!identity.active) throw new IllegalStateException("Kaggle token aktif değil.");
                if (identity.username.isEmpty()) throw new IllegalStateException("Kaggle kullanıcı adı token üzerinden alınamadı.");

                KaggleClient.Result validation = kaggle.validateToken(parsed);
                if (!validation.ok()) throw new IllegalStateException("Kaggle API doğrulaması HTTP " + validation.code);

                secure.put("kaggle_token", parsed);
                prefs.edit()
                        .putString("username", identity.username)
                        .putBoolean("waiting_for_kaggle_token", false)
                        .apply();
                getSharedPreferences("video_factory_settings", MODE_PRIVATE)
                        .edit().putString("username", identity.username).apply();

                ui(() -> {
                    username.setText(identity.username);
                    token.setText(parsed);
                    workInFlight = false;
                    if (connectKaggle != null) connectKaggle.setEnabled(true);
                    if (importTokenFile != null) importTokenFile.setEnabled(true);
                    toast("Kaggle bağlandı: " + identity.username);
                    render();
                    if (autoStart) handler.postDelayed(this::startLiveTest, 250L);
                });
            } catch (Exception e) {
                ui(() -> {
                    workInFlight = false;
                    if (connectKaggle != null) connectKaggle.setEnabled(true);
                    if (importTokenFile != null) importTokenFile.setEnabled(true);
                    fail("Kaggle bağlantısı doğrulanamadı: " + safe(e));
                });
            }
        });
    }

    private void startLiveTest() {
        String state = state();
        if (RUNNING.equals(state) || DOWNLOADING.equals(state) || workInFlight) {
            toast("Canlı test zaten devam ediyor.");
            return;
        }
        String user = username.getText().toString().trim();
        String tokenValue = token.getText().toString().trim();
        if (user.isEmpty() || tokenValue.isEmpty()) {
            toast("Kaggle kullanıcı adı ve API token gerekli.");
            return;
        }
        try {
            secure.put("kaggle_token", tokenValue);
            getSharedPreferences("video_factory_settings", MODE_PRIVATE)
                    .edit().putString("username", user).apply();
        } catch (Exception e) {
            fail("Token güvenli kaydedilemedi: " + safe(e));
            return;
        }

        workInFlight = true;
        status.setText("TOKEN DOĞRULANIYOR…");
        start.setEnabled(false);
        executor.execute(() -> {
            try {
                KaggleClient.Result auth = kaggle.validateToken(tokenValue);
                if (!auth.ok()) throw new IllegalStateException("Kaggle token HTTP " + auth.code);

                String stamp = String.valueOf(System.currentTimeMillis());
                String slug = "vf-e2e-" + stamp;
                String script = VideoFactoryScript.build(CANONICAL_STORY, slug);
                KaggleClient.PushResult pushed = kaggle.pushKernel(user, slug, slug, script, tokenValue);
                prefs.edit()
                        .putString("state", RUNNING)
                        .putString("username", user)
                        .putString("slug", slug)
                        .putInt("version", pushed.version)
                        .putLong("started_at", System.currentTimeMillis())
                        .putString("last_remote", "KUYRUKTA")
                        .putString("last_error", "")
                        .putString("certificate", "")
                        .putInt("download_attempts", 0)
                        .remove("download_id")
                        .apply();
                ui(() -> {
                    workInFlight = false;
                    render();
                    toast("Gerçek Kaggle E2E işi başlatıldı.");
                    handler.postDelayed(this::pollOnce, 5_000L);
                });
            } catch (Exception e) {
                ui(() -> {
                    workInFlight = false;
                    fail("Canlı test başlatılamadı: " + safe(e));
                });
            }
        });
    }

    private void pollOnce() {
        if (workInFlight) return;
        String current = state();
        if (RUNNING.equals(current)) {
            long started = prefs.getLong("started_at", 0L);
            if (started > 0L && System.currentTimeMillis() - started > MAX_RUN_MS) {
                fail("Canlı E2E 4 saatlik güvenlik sınırını aştı.");
                return;
            }
            String tokenValue = secure.get("kaggle_token");
            if (tokenValue.isEmpty()) {
                status.setText("TEST DEVAM EDİYOR — TOKEN YENİDEN GEREKLİ");
                return;
            }
            String user = prefs.getString("username", "");
            String slug = prefs.getString("slug", "");
            int version = prefs.getInt("version", 0);
            if (user.isEmpty() || slug.isEmpty() || version <= 0) {
                fail("Canlı test oturum bilgisi eksik.");
                return;
            }
            workInFlight = true;
            executor.execute(() -> pollRemote(user, slug, version, tokenValue));
        } else if (DOWNLOADING.equals(current)) {
            executor.execute(this::reconcileDownload);
        }
    }

    private void pollRemote(String user, String slug, int version, String tokenValue) {
        try {
            String remote = kaggle.getStatus(user, slug, tokenValue);
            prefs.edit().putString("last_remote", remote).apply();
            if (remote.startsWith("HATALI") || "DURDURULDU".equals(remote)) {
                ui(() -> {
                    workInFlight = false;
                    fail("Kaggle işi başarısız: " + remote);
                });
                return;
            }
            if (!"TAMAMLANDI".equals(remote)) {
                ui(() -> {
                    workInFlight = false;
                    render();
                });
                return;
            }

            LiveE2ECertificate certificate;
            try {
                KaggleClient.DownloadTarget target = kaggle.resolveOutputDownload(
                        user, slug, version, "status.json", tokenValue);
                KaggleClient.Result statusJson = kaggle.request(
                        "GET", target.url, target.authRequired ? tokenValue : null, null, true);
                if (!statusJson.ok()) throw new IllegalStateException("status.json HTTP " + statusJson.code);
                certificate = LiveE2ECertificate.parse(statusJson.body);
            } catch (Exception persistenceDelay) {
                // Kaggle can report the session complete a little before persisted outputs become
                // downloadable. This is retryable and must not turn a healthy AI run into FAIL.
                prefs.edit().putString("last_remote", "TAMAMLANDI — ÇIKTI KALICILAŞIYOR").apply();
                ui(() -> {
                    workInFlight = false;
                    render();
                });
                return;
            }

            if (!certificate.passesCanonicalV4()) {
                String why = certificate.failureReason();
                ui(() -> {
                    workInFlight = false;
                    fail("AI sertifika koşulu geçmedi: " + why);
                });
                return;
            }
            prefs.edit().putString("certificate", certificate.summary()).apply();
            KaggleClient.DownloadTarget finalTarget = kaggle.resolveOutputDownload(
                    user, slug, version, "FINAL.mp4", tokenValue);
            ui(() -> {
                try {
                    enqueueFinal(finalTarget, tokenValue, slug);
                    workInFlight = false;
                    render();
                } catch (Exception e) {
                    workInFlight = false;
                    fail("FINAL.mp4 indirmesi başlatılamadı: " + safe(e));
                }
            });
        } catch (Exception e) {
            prefs.edit().putString("last_error", safe(e)).apply();
            ui(() -> {
                workInFlight = false;
                render();
            });
        }
    }

    private void enqueueFinal(KaggleClient.DownloadTarget target, String tokenValue, String slug) {
        int attempts = prefs.getInt("download_attempts", 0) + 1;
        if (attempts > MAX_DOWNLOAD_ATTEMPTS) {
            fail("FINAL.mp4 indirme deneme sınırı aşıldı.");
            return;
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(target.url));
        if (target.authRequired && !tokenValue.isEmpty()) {
            request.addRequestHeader("Authorization", "Bearer " + tokenValue);
        }
        request.setTitle("Video Fabrikası E2E — " + slug);
        request.setDescription("Sertifika FINAL.mp4 indiriliyor");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, "VideoFabrikasi-E2E-" + slug + ".mp4");
        request.setMimeType("video/mp4");
        long id = ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
        prefs.edit()
                .putString("state", DOWNLOADING)
                .putLong("download_id", id)
                .putInt("download_attempts", attempts)
                .putString("last_error", "")
                .apply();
        toast("E2E FINAL.mp4 indiriliyor; Android tamamlanınca doğrulayacak.");
    }

    private void registerDownloadReceiver() {
        downloadReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long expected = prefs.getLong("download_id", -1L);
                long actual = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2L);
                if (expected <= 0L || expected != actual) return;
                executor.execute(thisActivity()::reconcileDownload);
            }
        };
        registerReceiver(downloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
    }

    private LiveE2EActivity thisActivity() {
        return this;
    }

    private void reconcileDownload() {
        if (!DOWNLOADING.equals(state())) return;
        long id = prefs.getLong("download_id", -1L);
        if (id <= 0L) {
            ui(() -> fail("E2E DownloadManager kimliği kayıp."));
            return;
        }
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (Cursor cursor = dm.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int downloadState = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (downloadState == DownloadManager.STATUS_PENDING
                    || downloadState == DownloadManager.STATUS_RUNNING
                    || downloadState == DownloadManager.STATUS_PAUSED) return;
            if (downloadState != DownloadManager.STATUS_SUCCESSFUL) {
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                retryFinalDownload("Android DownloadManager hata kodu " + reason);
                return;
            }
            long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            if (bytes >= 0L && bytes < 100_000L) {
                retryFinalDownload("İndirilen MP4 çok küçük: " + bytes + " bayt");
                return;
            }
            String local = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            if (local == null || local.isEmpty()) {
                retryFinalDownload("DownloadManager yerel URI döndürmedi");
                return;
            }
            verifyAndroidMedia(Uri.parse(local));
            prefs.edit()
                    .putString("state", PASS)
                    .putString("verified_uri", local)
                    .putLong("passed_at", System.currentTimeMillis())
                    .putString("last_error", "")
                    .apply();
            ui(() -> {
                render();
                toast("CANLI E2E PASS — gerçek GPU zinciri sertifikalandı.");
            });
        } catch (Exception e) {
            retryFinalDownload("Android medya doğrulaması: " + safe(e));
        }
    }

    private void verifyAndroidMedia(Uri uri) throws Exception {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, uri);
            int width = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            long duration = parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (width != 1080 || height != 1920) {
                throw new IllegalStateException("video=" + width + "x" + height + ", beklenen 1080x1920");
            }
            if (duration < 8_000L) {
                throw new IllegalStateException("süre=" + duration + " ms, beklenen >=8000 ms");
            }
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    private void retryFinalDownload(String cause) {
        prefs.edit().putString("last_error", cause).apply();
        int attempts = prefs.getInt("download_attempts", 0);
        if (attempts >= MAX_DOWNLOAD_ATTEMPTS) {
            ui(() -> fail("FINAL.mp4 üç indirme denemesinde doğrulanamadı: " + cause));
            return;
        }
        String user = prefs.getString("username", "");
        String slug = prefs.getString("slug", "");
        int version = prefs.getInt("version", 0);
        String tokenValue = secure.get("kaggle_token");
        if (user.isEmpty() || slug.isEmpty() || version <= 0 || tokenValue.isEmpty()) {
            ui(() -> fail("İndirme yeniden denemesi için oturum/token eksik: " + cause));
            return;
        }
        try {
            KaggleClient.DownloadTarget target = kaggle.resolveOutputDownload(
                    user, slug, version, "FINAL.mp4", tokenValue);
            ui(() -> {
                try {
                    enqueueFinal(target, tokenValue, slug);
                    render();
                } catch (Exception e) {
                    fail("İndirme yeniden başlatılamadı: " + safe(e));
                }
            });
        } catch (Exception e) {
            ui(() -> fail("FINAL.mp4 URL yeniden çözülemedi: " + safe(e)));
        }
    }

    private void fail(String message) {
        prefs.edit().putString("state", FAIL).putString("last_error", message).apply();
        render();
        toast(message);
    }

    private String state() {
        return prefs.getString("state", IDLE);
    }

    private void render() {
        String current = state();
        String remote = prefs.getString("last_remote", "");
        String error = prefs.getString("last_error", "");
        String cert = prefs.getString("certificate", "");
        String slug = prefs.getString("slug", "");
        int version = prefs.getInt("version", 0);
        long started = prefs.getLong("started_at", 0L);
        long passed = prefs.getLong("passed_at", 0L);

        if (PASS.equals(current)) status.setText("✅ CANLI E2E PASS");
        else if (FAIL.equals(current)) status.setText("❌ CANLI E2E FAIL");
        else if (DOWNLOADING.equals(current)) status.setText("⬇ FINAL.mp4 İNDİRİLİYOR / DOĞRULANIYOR");
        else if (RUNNING.equals(current)) status.setText("⚙ GERÇEK KAGGLE T4 TESTİ ÇALIŞIYOR");
        else status.setText("CANLI TEST HENÜZ YAPILMADI");

        StringBuilder b = new StringBuilder();
        if (!slug.isEmpty()) b.append("Proje: ").append(slug).append('\n');
        if (version > 0) b.append("Kaggle sürüm: ").append(version).append('\n');
        if (started > 0L) b.append("Başlangıç: ").append(formatTime(started)).append('\n');
        if (!remote.isEmpty()) b.append("Uzak durum: ").append(remote).append('\n');
        if (!cert.isEmpty()) b.append("AI sertifikası: ").append(cert).append('\n');
        if (passed > 0L) b.append("PASS zamanı: ").append(formatTime(passed)).append('\n');
        if (!error.isEmpty()) b.append("Son hata: ").append(error).append('\n');
        b.append("Durum: ").append(current);
        details.setText(b.toString());

        boolean active = RUNNING.equals(current) || DOWNLOADING.equals(current) || workInFlight;
        start.setEnabled(!active);
        if (connectKaggle != null) connectKaggle.setEnabled(!active);
        if (importTokenFile != null) importTokenFile.setEnabled(!active);
        start.setText(PASS.equals(current) ? "CANLI SİSTEM TESTİNİ TEKRARLA"
                : active ? "CANLI TEST DEVAM EDİYOR" : "CANLI SİSTEM TESTİNİ BAŞLAT");
    }

    private String formatTime(long millis) {
        try { return DateFormat.getDateTimeInstance().format(new Date(millis)); }
        catch (Exception e) { return String.valueOf(millis); }
    }

    private EditText edit(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setTextSize(16);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackgroundColor(Color.WHITE);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        return button;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(20, 20, 20));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams full() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int parseInt(String value) {
        try { return Integer.parseInt(value == null ? "0" : value); }
        catch (Exception e) { return 0; }
    }

    private long parseLong(String value) {
        try { return Long.parseLong(value == null ? "0" : value); }
        catch (Exception e) { return 0L; }
    }

    private static String safe(Exception e) {
        if (e == null) return "bilinmeyen hata";
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) message = e.toString();
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void ui(Runnable runnable) {
        runOnUiThread(runnable);
    }
}
