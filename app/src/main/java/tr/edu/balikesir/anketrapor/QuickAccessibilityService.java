package tr.edu.balikesir.anketrapor;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.Toast;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public class QuickAccessibilityService extends AccessibilityService {
    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final String YOUTUBE_PACKAGE = "com.google.android.youtube";
    private static final String KEY_LAST_VIDEO = "last_normal_video";

    // Normal YouTube videoları: yapım, üretim, ahşap ve restorasyon tarzı; Shorts değildir.
    private static final String[] NORMAL_VIDEO_IDS = new String[] {
            "x5y_IsR53cU", // Wood carving - no talking
            "cPUhejwl-cs", // Satisfying manufacturing processes
            "5siCeReHfJ8", // Recycling/manufacturing processes
            "1tpfIXvates", // Wooden toy making - no talking
            "oxvG9QcYbUg", // Silent spoon carving
            "eWE9X1iCedk", // Chair build - no talking
            "49gKJ7GAbBg", // Japanese woodworking - no talking
            "GqL6adoH4Tk", // Jewelry box build - no talking
            "4PdjRDZXU-I"  // Wooden bench making - no talking
    };

    private WindowManager windowManager;
    private Button bubble;
    private WindowManager.LayoutParams params;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean automationRunning = false;
    private int phase = 0; // 0: force stop, 1: confirm, 2: verify stopped
    private int attempts = 0;
    private int detailRounds = 0;
    private String currentTarget;
    private Deque<String> targets;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        showBubble();
    }

    private void showBubble() {
        if (bubble != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        bubble = new Button(this);
        bubble.setText("Kapat");
        bubble.setTextSize(11f);
        bubble.setAllCaps(false);
        bubble.setAlpha(0.88f);
        bubble.setPadding(dp(6), 0, dp(6), 0);
        bubble.setMinWidth(0);
        bubble.setMinimumWidth(0);
        bubble.setMinHeight(0);
        bubble.setMinimumHeight(0);

        params = new WindowManager.LayoutParams(
                dp(58), dp(38),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.x = dp(6);
        params.y = 0;

        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float startRawX;
            private float startRawY;
            private int startX;
            private int startY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawX = event.getRawX();
                        startRawY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - startRawX;
                        float dy = event.getRawY() - startRawY;
                        if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) moved = true;
                        params.x = Math.max(0, startX - Math.round(dx));
                        params.y = startY + Math.round(dy);
                        try { windowManager.updateViewLayout(bubble, params); } catch (Exception ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) closeChromeVpnAndOpenNormalVideo();
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            windowManager.addView(bubble, params);
        } catch (Exception e) {
            Toast.makeText(this, "Kapat düğmesi gösterilemedi.", Toast.LENGTH_SHORT).show();
        }
    }

    private void closeChromeVpnAndOpenNormalVideo() {
        if (automationRunning) return;
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String vpnPackage = prefs.getString(MainActivity.KEY_VPN_PACKAGE, "");

        targets = new ArrayDeque<>();
        targets.add(CHROME_PACKAGE);
        if (!TextUtils.isEmpty(vpnPackage) && !CHROME_PACKAGE.equals(vpnPackage)) {
            targets.add(vpnPackage);
        }

        automationRunning = true;
        performGlobalAction(GLOBAL_ACTION_HOME);
        handler.postDelayed(this::openNextAppDetails, 100);
    }

    private void openNextAppDetails() {
        if (!automationRunning) return;
        currentTarget = targets.pollFirst();
        if (currentTarget == null) {
            finishAutomation();
            return;
        }
        detailRounds = 0;
        openCurrentDetails();
    }

    private void openCurrentDetails() {
        phase = 0;
        attempts = 0;
        try {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + currentTarget));
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(details);
            handler.postDelayed(this::pumpForceStop, 250);
        } catch (Exception e) {
            handler.postDelayed(this::openNextAppDetails, 120);
        }
    }

    private void pumpForceStop() {
        if (!automationRunning) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            retryCurrent();
            return;
        }

        AccessibilityNodeInfo forceStop = findNode(root,
                "zorla durdur", "force stop", "durdurmaya zorla");

        if (phase == 0) {
            // Düğme pasifse uygulama zaten tamamen durdurulmuş demektir.
            if (forceStop != null && !forceStop.isEnabled()) {
                handler.postDelayed(this::openNextAppDetails, 150);
                return;
            }
            if (forceStop != null && forceStop.isEnabled() && clickNode(forceStop)) {
                phase = 1;
                attempts = 0;
                handler.postDelayed(this::pumpForceStop, 220);
                return;
            }
            retryCurrent();
            return;
        }

        if (phase == 1) {
            // Bazı cihazlarda ikinci onay çıkmadan doğrudan durur.
            if (forceStop != null && !forceStop.isEnabled()) {
                handler.postDelayed(this::openNextAppDetails, 150);
                return;
            }

            AccessibilityNodeInfo confirm = findNode(root,
                    "tamam", "ok", "evet", "yes");
            if (confirm != null && confirm.isEnabled() && clickNode(confirm)) {
                phase = 2;
                attempts = 0;
                handler.postDelayed(this::pumpForceStop, 300);
                return;
            }

            attempts++;
            if (attempts <= 8) {
                handler.postDelayed(this::pumpForceStop, 180);
            } else {
                retryDetailsRound();
            }
            return;
        }

        // phase 2: Zorla durdur düğmesinin gerçekten pasif olduğunu doğrula.
        if (forceStop != null && !forceStop.isEnabled()) {
            handler.postDelayed(this::openNextAppDetails, 160);
            return;
        }

        // Onay diyaloğu hâlâ açıksa bir kez daha pozitif düğmeyi dene.
        AccessibilityNodeInfo confirm = findNode(root, "tamam", "ok", "evet", "yes");
        if (confirm != null && confirm.isEnabled()) clickNode(confirm);

        attempts++;
        if (attempts <= 10) {
            handler.postDelayed(this::pumpForceStop, 220);
        } else {
            retryDetailsRound();
        }
    }

    private void retryCurrent() {
        attempts++;
        if (attempts <= 8) {
            handler.postDelayed(this::pumpForceStop, 180);
        } else {
            retryDetailsRound();
        }
    }

    private void retryDetailsRound() {
        detailRounds++;
        if (detailRounds <= 2) {
            handler.postDelayed(this::openCurrentDetails, 180);
        } else {
            Toast.makeText(this,
                    "Bir uygulamanın Zorla durdur durumu doğrulanamadı; sıradaki adıma geçiliyor.",
                    Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::openNextAppDetails, 180);
        }
    }

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo node, String... wanted) {
        if (node == null) return null;
        String text = normalize(node.getText());
        String desc = normalize(node.getContentDescription());
        for (String item : wanted) {
            String key = normalize(item);
            if ((!TextUtils.isEmpty(text) && text.contains(key)) ||
                    (!TextUtils.isEmpty(desc) && desc.contains(key))) {
                return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNode(node.getChild(i), wanted);
            if (found != null) return found;
        }
        return null;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isClickable()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private String normalize(CharSequence value) {
        if (value == null) return "";
        return value.toString().trim().toLowerCase(new Locale("tr", "TR"));
    }

    private void finishAutomation() {
        automationRunning = false;
        phase = 0;
        currentTarget = null;
        performGlobalAction(GLOBAL_ACTION_HOME);
        handler.postDelayed(() -> openRandomNormalVideo(this), 140);
    }

    public static void openRandomNormalVideo(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String last = prefs.getString(KEY_LAST_VIDEO, "");
        SecureRandom random = new SecureRandom();
        String selected = NORMAL_VIDEO_IDS[random.nextInt(NORMAL_VIDEO_IDS.length)];
        if (NORMAL_VIDEO_IDS.length > 1) {
            int guard = 0;
            while (selected.equals(last) && guard < 12) {
                selected = NORMAL_VIDEO_IDS[random.nextInt(NORMAL_VIDEO_IDS.length)];
                guard++;
            }
        }
        prefs.edit().putString(KEY_LAST_VIDEO, selected).apply();

        // vnd.youtube açılışı normal uzun-form YouTube oynatıcısını hedefler, Shorts değil.
        try {
            Intent youtube = new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + selected));
            youtube.setPackage(YOUTUBE_PACKAGE);
            youtube.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(youtube);
            return;
        } catch (Exception ignored) { }

        Uri watchUri = Uri.parse("https://www.youtube.com/watch?v=" + selected);
        try {
            Intent youtubeWeb = new Intent(Intent.ACTION_VIEW, watchUri);
            youtubeWeb.setPackage(YOUTUBE_PACKAGE);
            youtubeWeb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(youtubeWeb);
            return;
        } catch (Exception ignored) { }

        try {
            Intent web = new Intent(Intent.ACTION_VIEW, watchUri);
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(web);
        } catch (Exception e) {
            try {
                Intent market = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + YOUTUBE_PACKAGE));
                market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(market);
            } catch (Exception ignored) { }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (automationRunning) handler.postDelayed(this::pumpForceStop, 50);
    }

    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        removeBubble();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        removeBubble();
        super.onDestroy();
    }

    private void removeBubble() {
        if (bubble != null && windowManager != null) {
            try { windowManager.removeView(bubble); } catch (Exception ignored) { }
            bubble = null;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
