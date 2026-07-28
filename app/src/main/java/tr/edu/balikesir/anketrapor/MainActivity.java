package tr.edu.balikesir.anketrapor;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends Activity {
    private GameView game;
    private LinearLayout controls;
    private TextView status;
    private Button botButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        }

        FrameLayout root = new FrameLayout(this);
        game = new GameView();
        root.addView(game, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(Color.rgb(62, 42, 31));
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0xDDFBF3DE);
        status.setPadding(14, 8, 14, 8);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sp.topMargin = 18;
        root.addView(status, sp);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(12, 10, 12, 16);
        controls.setBackgroundColor(0xDDF7E4C0);

        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER);
        Button left = button("⚡ SOL");
        Button step = button("ADIM AT");
        Button right = button("SAĞ ⚡");
        row1.addView(left, weight()); row1.addView(step, weight()); row1.addView(right, weight());

        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER);
        botButton = button("🤖 BOT");
        Button video = button("🎥 15 SN VİDEO");
        Button reset = button("↺ YENİDEN");
        row2.addView(botButton, weight()); row2.addView(video, weight()); row2.addView(reset, weight());
        controls.addView(row1); controls.addView(row2);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(controls, cp);
        setContentView(root);

        left.setOnClickListener(v -> game.moveStone(-1));
        right.setOnClickListener(v -> game.moveStone(1));
        step.setOnClickListener(v -> game.tryStep(game.stoneLane));
        reset.setOnClickListener(v -> game.resetGame());
        botButton.setOnClickListener(v -> {
            game.bot = !game.bot;
            botButton.setText(game.bot ? "⏸ BOTU DURDUR" : "🤖 BOT");
            game.message = game.bot ? "Bot rotayı hesaplıyor" : "Sıra sende";
        });
        video.setOnClickListener(v -> game.startStudioRecording());
        updateStatus();
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(5, 4, 5, 4);
        return p;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(Color.rgb(54, 38, 27));
        b.setBackgroundColor(Color.WHITE);
        b.setMinHeight(58);
        return b;
    }

    private void updateStatus() {
        if (status != null && game != null) {
            status.setText(String.format(Locale.forLanguageTag("tr-TR"), "YÜKSEKLİK %d  •  TAŞ: %s  •  %s", game.height, GameView.LANE_NAMES[game.stoneLane], game.message));
        }
    }

    private final class GameView extends View {
        static final int BASE_W = 720;
        static final int BASE_H = 1280;
        static final int ROWS = 12;
        static final String[] LANE_NAMES = {"SOL", "ORTA", "SAĞ"};

        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Random random = new Random(731947L);
        final boolean[][] steps = new boolean[ROWS][3];
        final int[] shift = new int[ROWS];
        final float[] laneX = {155f, 360f, 565f};
        final List<Spark> sparks = new ArrayList<>();

        int height = 0;
        int playerLane = 1;
        int stoneLane = 1;
        int nextLane = 1;
        boolean alive = true;
        boolean bot = false;
        String message = "Taşı hizala ve basamağa dokun";

        long lastFrame;
        long botAt;
        long animStart;
        int animType;
        float animT;
        float scroll;
        float faceMood;

        MediaRecorder recorder;
        Surface recordSurface;
        File recordFile;
        boolean recording;
        long recordEnd;

        GameView() {
            super(MainActivity.this);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            resetGame();
        }

        void resetGame() {
            stopRecording(false);
            height = 0;
            playerLane = stoneLane = nextLane = 1;
            alive = true;
            animType = 0;
            animT = scroll = 0;
            sparks.clear();
            message = "Taşı hizala ve basamağa dokun";
            generateInitial();
            updateStatus();
            invalidate();
        }

        void generateInitial() {
            int c = playerLane;
            for (int r = 0; r < ROWS; r++) {
                generateRow(r, c);
                c = chooseGenerationLane(r, c);
            }
        }

        void generateRow(int row, int from) {
            for (int l = 0; l < 3; l++) steps[row][l] = false;
            int[] candidates = new int[3];
            int count = 0;
            for (int l = Math.max(0, from - 1); l <= Math.min(2, from + 1); l++) candidates[count++] = l;
            int safe = candidates[random.nextInt(count)];
            steps[row][safe] = true;
            if (random.nextFloat() < 0.78f) steps[row][candidates[random.nextInt(count)]] = true;
            if (height > 18 && random.nextFloat() < 0.38f) steps[row][candidates[random.nextInt(count)]] = true;
            shift[row] = height < 12 ? 0 : random.nextInt(3) - 1;
            if (safe + shift[row] < 0 || safe + shift[row] > 2) shift[row] = 0;
        }

        int chooseGenerationLane(int row, int from) {
            int best = from;
            int bestScore = -99;
            for (int l = 0; l < 3; l++) {
                if (!steps[row][l] || Math.abs(l - from) > 1) continue;
                int score = (l == 1 ? 3 : 0) + random.nextInt(3);
                if (score > bestScore) { bestScore = score; best = l; }
            }
            return best;
        }

        void moveStone(int dir) {
            if (!alive || animType != 0 || recording) return;
            stoneLane = Math.max(0, Math.min(2, stoneLane + dir));
            message = "Taş " + LANE_NAMES[stoneLane].toLowerCase(Locale.ROOT) + " şeritte";
            updateStatus();
            invalidate();
        }

        void tryStep(int lane) {
            if (!alive || animType != 0) return;
            if (lane < 0 || lane > 2 || !steps[0][lane] || Math.abs(lane - playerLane) > 1) {
                message = "Memo oraya yetişemedi";
                faceMood = -0.5f;
                updateStatus();
                invalidate();
                return;
            }
            nextLane = lane;
            if (stoneLane != lane) {
                startDeath();
            } else {
                animType = 1;
                animStart = System.currentTimeMillis();
                message = "Güvenli adım";
                updateStatus();
            }
        }

        void startDeath() {
            alive = false;
            animType = 3;
            animStart = System.currentTimeMillis();
            message = "CIZZZT! Yanlış basamak";
            for (int i = 0; i < 35; i++) sparks.add(new Spark(laneX[nextLane], 930, (random.nextFloat() - .5f) * 15f, -random.nextFloat() * 15f - 2f));
            updateStatus();
        }

        void completeClimb() {
            playerLane = nextLane;
            stoneLane = Math.max(0, Math.min(2, playerLane + shift[0]));
            height++;
            for (int r = 0; r < ROWS - 1; r++) {
                System.arraycopy(steps[r + 1], 0, steps[r], 0, 3);
                shift[r] = shift[r + 1];
            }
            generateRow(ROWS - 1, chooseTailLane());
            animType = 2;
            animStart = System.currentTimeMillis();
            scroll = 0;
            message = shift[0] == 0 ? "Yukarı devam" : "Basamak taşı kaydırdı";
            updateStatus();
        }

        int chooseTailLane() {
            int c = playerLane;
            for (int r = 0; r < ROWS - 1; r++) {
                for (int l = 0; l < 3; l++) if (steps[r][l] && Math.abs(l - c) <= 1) { c = l; break; }
            }
            return c;
        }

        int bestBotLane() {
            int bestLane = playerLane;
            int best = -1;
            for (int l = 0; l < 3; l++) {
                if (!steps[0][l] || Math.abs(l - playerLane) > 1) continue;
                int d = searchDepth(1, l, l, 8);
                if (d > best || (d == best && Math.abs(l - 1) < Math.abs(bestLane - 1))) {
                    best = d;
                    bestLane = l;
                }
            }
            return bestLane;
        }

        int searchDepth(int row, int c, int s, int limit) {
            if (row >= Math.min(ROWS, limit)) return row;
            int best = row;
            for (int l = 0; l < 3; l++) {
                if (!steps[row][l] || Math.abs(l - c) > 1 || Math.abs(l - s) > 1) continue;
                int ns = Math.max(0, Math.min(2, l + shift[row]));
                best = Math.max(best, searchDepth(row + 1, l, ns, limit));
            }
            return best;
        }

        void botTick(long now) {
            if (!bot || !alive || animType != 0 || now < botAt) return;
            int target = bestBotLane();
            if (stoneLane < target) moveStone(1);
            else if (stoneLane > target) moveStone(-1);
            else tryStep(target);
            botAt = now + (recording ? 390 : 560);
        }

        void startStudioRecording() {
            if (recording) return;
            try {
                recordFile = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "basma-yanarsin-" + System.currentTimeMillis() + ".mp4");
                recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(MainActivity.this) : new MediaRecorder();
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                recorder.setVideoEncodingBitRate(6_000_000);
                recorder.setVideoFrameRate(30);
                recorder.setVideoSize(BASE_W, BASE_H);
                recorder.setOutputFile(recordFile.getAbsolutePath());
                recorder.prepare();
                recordSurface = recorder.getSurface();
                recorder.start();
                recording = true;
                recordEnd = System.currentTimeMillis() + 15_000;
                bot = true;
                botButton.setText("⏸ BOTU DURDUR");
                controls.setVisibility(INVISIBLE);
                status.setVisibility(INVISIBLE);
                message = "Video kaydı";
                Toast.makeText(MainActivity.this, "15 saniyelik bot kaydı başladı", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                recording = false;
                recordSurface = null;
                if (recorder != null) { try { recorder.release(); } catch (Exception ignored) {} }
                recorder = null;
                Toast.makeText(MainActivity.this, "Video kaydı açılamadı: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        void stopRecording(boolean save) {
            if (!recording && recorder == null) return;
            recording = false;
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                try { recorder.reset(); recorder.release(); } catch (Exception ignored) {}
            }
            recorder = null;
            recordSurface = null;
            controls.setVisibility(VISIBLE);
            status.setVisibility(VISIBLE);
            if (save && recordFile != null && recordFile.exists() && recordFile.length() > 1000) saveToGallery(recordFile);
        }

        void saveToGallery(File source) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, source.getName());
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                if (Build.VERSION.SDK_INT >= 29) {
                    v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Basma Yanarsin");
                    v.put(MediaStore.Video.Media.IS_PENDING, 1);
                }
                Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("Galeri konumu açılamadı");
                try (FileInputStream in = new FileInputStream(source); OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("Video akışı açılamadı");
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    v.clear(); v.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, v, null, null);
                }
                Toast.makeText(MainActivity.this, "Video Galeri > Filmler > Basma Yanarsın klasörüne kaydedildi", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Video kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            long now = System.currentTimeMillis();
            float dt = lastFrame == 0 ? .016f : Math.min(.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            updateGame(now, dt);
            drawScaled(canvas, getWidth(), getHeight(), false);
            if (recording && recordSurface != null) {
                Canvas rc = null;
                try {
                    rc = Build.VERSION.SDK_INT >= 23 ? recordSurface.lockHardwareCanvas() : recordSurface.lockCanvas(null);
                    drawScene(rc, true);
                } catch (Exception ignored) {
                } finally {
                    if (rc != null) try { recordSurface.unlockCanvasAndPost(rc); } catch (Exception ignored) {}
                }
            }
            postInvalidateOnAnimation();
        }

        void updateGame(long now, float dt) {
            botTick(now);
            if (recording && now >= recordEnd) stopRecording(true);
            if (animType != 0) {
                animT = Math.min(1f, (now - animStart) / (animType == 3 ? 1050f : 340f));
                if (animType == 1 && animT >= 1) completeClimb();
                else if (animType == 2) {
                    scroll = animT * 78f;
                    if (animT >= 1) { animType = 0; scroll = 0; }
                } else if (animType == 3 && animT >= 1) {
                    animType = 0;
                    postDelayed(this::resetGame, 650);
                }
            }
            for (int i = sparks.size() - 1; i >= 0; i--) {
                Spark s = sparks.get(i); s.x += s.vx; s.y += s.vy; s.vy += .65f; s.life -= dt;
                if (s.life <= 0) sparks.remove(i);
            }
            faceMood *= .94f;
        }

        void drawScaled(Canvas c, int w, int h, boolean clean) {
            c.save();
            float scale = Math.min(w / (float) BASE_W, h / (float) BASE_H);
            float ox = (w - BASE_W * scale) / 2f;
            float oy = (h - BASE_H * scale) / 2f;
            c.translate(ox, oy); c.scale(scale, scale);
            drawScene(c, clean);
            c.restore();
        }

        void drawScene(Canvas c, boolean clean) {
            drawSky(c);
            drawCity(c);
            drawTower(c);
            drawSteps(c);
            drawStone(c);
            drawMemo(c);
            drawVillain(c);
            drawSparks(c);
            p.setColor(0xE8FFF7E9); round(c, 32, 24, 688, 108, 26, p);
            p.setColor(Color.rgb(57, 39, 28)); p.setTextAlign(Paint.Align.CENTER); p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            p.setTextSize(29); c.drawText("BASMA YANARSIN!", 360, 59, p);
            p.setTextSize(22); c.drawText("YÜKSEKLİK  " + height, 360, 91, p);
            if (recording || clean) {
                p.setColor(0xD9000000); round(c, 535, 28, 684, 75, 22, p);
                p.setColor(Color.WHITE); p.setTextSize(18); c.drawText("BOT KAYDI", 610, 58, p);
            }
        }

        void drawSky(Canvas c) {
            c.drawColor(Color.rgb(190, 232, 249));
            p.setColor(0x88FFFFFF);
            c.drawCircle(100, 165, 55, p); c.drawCircle(155, 150, 72, p); c.drawCircle(220, 174, 48, p);
            c.drawCircle(510, 205, 45, p); c.drawCircle(555, 185, 66, p); c.drawCircle(625, 205, 48, p);
            p.setColor(Color.rgb(255, 231, 172)); c.drawRect(0, 610, BASE_W, BASE_H, p);
        }

        void drawCity(Canvas c) {
            float parallax = height * 2.2f;
            for (int i = 0; i < 7; i++) {
                float x = 10 + i * 112;
                float h = 160 + (i % 3) * 42;
                float y = 870 + (parallax % 120) - h;
                p.setColor(i % 2 == 0 ? Color.rgb(216, 154, 105) : Color.rgb(232, 181, 126));
                c.drawRect(x, y, x + 90, 910 + (parallax % 120), p);
                p.setColor(Color.rgb(161, 88, 58));
                Path roof = new Path(); roof.moveTo(x - 8, y); roof.lineTo(x + 45, y - 42); roof.lineTo(x + 98, y); roof.close(); c.drawPath(roof, p);
            }
            p.setColor(Color.rgb(153, 109, 73)); c.drawRect(0, 900, BASE_W, BASE_H, p);
            p.setColor(0x22000000);
            for (int y = 920; y < BASE_H; y += 42) for (int x = (y / 3) % 60; x < BASE_W; x += 60) c.drawRect(x, y, x + 48, y + 23, p);
        }

        void drawTower(Canvas c) {
            p.setColor(Color.rgb(117, 119, 125)); round(c, 305, 180, 415, 905, 18, p);
            p.setColor(Color.rgb(88, 90, 96));
            for (int i = 0; i < 5; i++) c.drawRect(306 + i * 23, 160, 325 + i * 23, 200, p);
            stroke.setColor(0x33000000); stroke.setStrokeWidth(2);
            for (int y = 205; y < 900; y += 24) c.drawLine(307, y, 414, y, stroke);
            p.setColor(Color.rgb(41, 35, 46)); round(c, 325, 270, 395, 400, 24, p);
            drawCaptive(c, 360, 328);
            stroke.setColor(Color.rgb(30, 28, 31)); stroke.setStrokeWidth(7);
            for (int x = 337; x <= 383; x += 15) c.drawLine(x, 277, x, 395, stroke);
        }

        void drawCaptive(Canvas c, float x, float y) {
            float bob = (float) Math.sin(System.currentTimeMillis() / 280.0) * 3;
            c.save(); c.translate(x, y + bob);
            p.setColor(Color.rgb(151, 103, 211)); round(c, -20, 8, 20, 62, 13, p);
            stroke.setColor(Color.rgb(241, 194, 136)); stroke.setStrokeWidth(8);
            c.drawLine(-13, 18, -38, 8, stroke); c.drawLine(13, 18, 38, 1, stroke);
            p.setColor(Color.rgb(241, 194, 136)); c.drawCircle(0, -12, 22, p);
            p.setColor(Color.rgb(91, 55, 34)); c.drawArc(new RectF(-22, -35, 22, 4), 180, 180, true, p);
            p.setColor(Color.DKGRAY); c.drawCircle(-7, -13, 3, p); c.drawCircle(7, -13, 3, p);
            stroke.setColor(Color.rgb(126, 45, 28)); stroke.setStrokeWidth(3); c.drawArc(new RectF(-8, -5, 8, 9), 185, 170, false, stroke);
            c.restore();
        }

        float stepY(int row) { return 905 - row * 78 + scroll; }

        void drawSteps(Canvas c) {
            for (int r = ROWS - 1; r >= 0; r--) {
                float y = stepY(r);
                float w = Math.max(70, 122 - r * 4);
                for (int l = 0; l < 3; l++) {
                    if (!steps[r][l]) continue;
                    float x = laneX[l];
                    p.setColor(0x25000000); round(c, x - w / 2 + 5, y - 7 + 8, x + w / 2 + 5, y + 12 + 8, 10, p);
                    p.setColor(r == 0 && l == stoneLane ? Color.rgb(137, 236, 164) : Color.rgb(255, 247, 229));
                    round(c, x - w / 2, y - 7, x + w / 2, y + 12, 10, p);
                    stroke.setColor(Color.rgb(116, 77, 48)); stroke.setStrokeWidth(3); roundStroke(c, x - w / 2, y - 7, x + w / 2, y + 12, 10, stroke);
                    if (shift[r] != 0) {
                        p.setColor(Color.rgb(82, 109, 155)); p.setTextSize(18); p.setTextAlign(Paint.Align.CENTER);
                        c.drawText(shift[r] < 0 ? "←" : "→", x, y + 7, p);
                    }
                }
            }
        }

        void drawStone(Canvas c) {
            float x = laneX[stoneLane], y = stepY(0) + 38;
            p.setColor(Color.rgb(48, 199, 239)); c.drawCircle(x, y, 20, p);
            stroke.setColor(Color.rgb(11, 82, 107)); stroke.setStrokeWidth(4); c.drawCircle(x, y, 20, stroke);
            p.setColor(Color.WHITE); c.drawCircle(x - 7, y - 5, 3, p); c.drawCircle(x + 7, y - 5, 3, p);
            stroke.setColor(Color.rgb(11, 82, 107)); stroke.setStrokeWidth(3); c.drawArc(new RectF(x - 8, y, x + 8, y + 12), 0, 180, false, stroke);
            stroke.setColor(Color.rgb(255, 236, 48)); stroke.setStrokeWidth(4);
            for (int i = -1; i <= 1; i++) { float dx = i * 12; Path z = new Path(); z.moveTo(x + dx - 5, y - 27); z.lineTo(x + dx + 1, y - 36); z.lineTo(x + dx + 5, y - 25); c.drawPath(z, stroke); }
        }

        void drawMemo(Canvas c) {
            float x = laneX[playerLane], y = 855;
            if (animType == 1) {
                float e = ease(animT); x = laneX[playerLane] + (laneX[nextLane] - laneX[playerLane]) * e; y -= 78 * e + (float) Math.sin(e * Math.PI) * 25;
            } else if (animType == 3) { x = laneX[nextLane]; y -= (float) Math.sin(animT * Math.PI) * 12; }
            c.save(); c.translate(x, y);
            if (animType == 3) c.rotate((random.nextFloat() - .5f) * 10);
            drawMemoBody(c, animType == 3);
            c.restore();
        }

        void drawMemoBody(Canvas c, boolean shocked) {
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(Color.rgb(65, 48, 34)); stroke.setStrokeWidth(10);
            c.drawLine(-9, 35, -14, 68, stroke); c.drawLine(9, 35, 14, 68, stroke);
            p.setColor(Color.rgb(248, 239, 219)); round(c, -24, -4, 24, 43, 15, p);
            p.setColor(Color.rgb(194, 64, 58)); c.drawRect(-19, 12, 19, 25, p);
            stroke.setColor(Color.rgb(241, 194, 136)); stroke.setStrokeWidth(9);
            c.drawLine(-23, 10, -40, shocked ? -2 : 25, stroke); c.drawLine(23, 10, 40, shocked ? -4 : 24, stroke);
            p.setColor(Color.rgb(241, 194, 136)); c.drawCircle(0, -31, 29, p);
            p.setColor(Color.rgb(77, 47, 27)); c.drawArc(new RectF(-29, -62, 29, -12), 180, 180, true, p);
            if (shocked) for (int i = -22; i <= 22; i += 9) c.drawRect(i, -76, i + 5, -54, p);
            p.setColor(Color.rgb(36, 30, 26));
            if (shocked) { c.drawRect(-14, -34, 10, 4, p); c.drawRect(5, -34, 10, 4, p); }
            else { c.drawCircle(-9, -33, 4, p); c.drawCircle(9, -33, 4, p); }
            stroke.setColor(Color.rgb(54, 38, 25)); stroke.setStrokeWidth(4);
            c.drawLine(-17, -44, -5, -47 - faceMood * 4, stroke); c.drawLine(5, -47 - faceMood * 4, 17, -44, stroke);
            p.setColor(Color.rgb(68, 40, 23)); c.drawOval(new RectF(-17, -23, 0, -12), p); c.drawOval(new RectF(0, -23, 17, -12), p);
            if (shocked) { p.setColor(Color.rgb(25, 18, 13)); c.drawCircle(0, -4, 10, p); p.setColor(0x66000000); c.drawCircle(0, -24, 25, p); }
            else { stroke.setColor(Color.rgb(132, 47, 26)); stroke.setStrokeWidth(4); c.drawArc(new RectF(-10, -10, 10, 8), 0, 180, false, stroke); }
        }

        void drawVillain(Canvas c) {
            float x = 615, y = 830 + (float) Math.sin(System.currentTimeMillis() / 320.0) * 4;
            c.save(); c.translate(x, y);
            p.setColor(Color.rgb(71, 37, 80)); round(c, -30, 0, 30, 82, 17, p);
            stroke.setColor(Color.rgb(42, 31, 26)); stroke.setStrokeWidth(9); c.drawLine(-14, 78, -14, 108, stroke); c.drawLine(14, 78, 14, 108, stroke);
            p.setColor(Color.rgb(241, 194, 136)); c.drawCircle(0, -29, 28, p);
            p.setColor(Color.rgb(31, 23, 18)); c.drawArc(new RectF(-28, -58, 28, -10), 180, 180, true, p);
            p.setColor(Color.rgb(31, 23, 18)); c.drawCircle(-8, -31, 3, p); c.drawCircle(8, -31, 3, p);
            p.setColor(Color.rgb(66, 39, 22)); c.drawOval(new RectF(-17, -20, 0, -10), p); c.drawOval(new RectF(0, -20, 17, -10), p);
            stroke.setColor(Color.rgb(135, 46, 29)); stroke.setStrokeWidth(4); c.drawArc(new RectF(-12, -9, 12, 10), 0, 180, false, stroke);
            stroke.setColor(Color.rgb(241, 194, 136)); stroke.setStrokeWidth(9); c.drawLine(26, 20, 47, 5, stroke); c.drawLine(47, 5, 58, -7, stroke);
            c.restore();
            p.setColor(Color.rgb(92, 41, 93)); p.setTextSize(18); p.setTextAlign(Paint.Align.CENTER); c.drawText("HA HA!", 615, 685, p);
        }

        void drawSparks(Canvas c) {
            stroke.setColor(Color.YELLOW); stroke.setStrokeWidth(4);
            for (Spark s : sparks) { Path z = new Path(); z.moveTo(s.x - 6, s.y); z.lineTo(s.x, s.y - 9); z.lineTo(s.x + 6, s.y); c.drawPath(z, stroke); }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP || recording) return true;
            float sx = e.getX() * BASE_W / getWidth();
            float sy = e.getY() * BASE_H / getHeight();
            if (sy > stepY(0) - 55 && sy < stepY(0) + 45) {
                int lane = sx < 255 ? 0 : sx > 465 ? 2 : 1;
                tryStep(lane);
            }
            return true;
        }

        float ease(float t) { return t < .5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2; }
        void round(Canvas c, float l, float t, float r, float b, float rad, Paint paint) { c.drawRoundRect(new RectF(l, t, r, b), rad, rad, paint); }
        void roundStroke(Canvas c, float l, float t, float r, float b, float rad, Paint paint) { c.drawRoundRect(new RectF(l, t, r, b), rad, rad, paint); }

        final class Spark {
            float x, y, vx, vy, life = 1.2f;
            Spark(float x, float y, float vx, float vy) { this.x = x; this.y = y; this.vx = vx; this.vy = vy; }
        }
    }

    @Override protected void onPause() {
        if (game != null) game.stopRecording(true);
        super.onPause();
    }
}
