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
    private static final String KEY_LAST_SHORT = "last_funny_short";

    // Güncel ve doğrulanmış komik Shorts havuzu. Her tetiklemede bir önceki video tekrarlanmaz.
    private static final String[] FUNNY_SHORT_IDS = new String[] {
            "Z_bMPmW_n6A", // Funny animals
            "Xp2YQawe19A", // Funny Animals #30
            "fEmjqcCEf1M", // Funny animals videos
            "omW3uK0BRpI"  // Funny cat and dog videos
    };

    private WindowManager windowManager;
    private Button bubble;
    private WindowManager.LayoutParams params;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();

    private boolean automationRunning = false;
    private boolean waitingConfirmation = false;
    private int attempts = 0;
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
                dp(58),
                dp(38),
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
                        if (!moved) closeChromeVpnAndOpenFunnyShort();
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

    private void closeChromeVpnAndOpenFunnyShort() {
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
        handler.postDelayed(this::openNextAppDetails, 80);
    }

    private void openNextAppDetails() {
        if (!automationRunning) return;

        currentTarget = targets.pollFirst();
        if (currentTarget == null) {
            finishAutomation();
            return;
        }

        waitingConfirmation = false;
        attempts = 0;

        try {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + currentTarget));
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(details);
            handler.postDelayed(this::pumpForceStop, 180);
        } catch (Exception e) {
            handler.postDelayed(this::openNextAppDetails, 80);
        }
    }

    private void pumpForceStop() {
        if (!automationRunning) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            retryOrAdvance();
            return;
        }

        if (!waitingConfirmation) {
            AccessibilityNodeInfo forceStop = findNode(root,
                    "zorla durdur", "force stop", "durdurmaya zorla");

            if (forceStop != null && forceStop.isEnabled() && clickNode(forceStop)) {
                waitingConfirmation = true;
                attempts = 0;
                handler.postDelayed(this::pumpForceStop, 130);
                return;
            }

            retryOrAdvance();
            return;
        }

        AccessibilityNodeInfo confirm = findNode(root,
                "tamam", "ok", "evet", "yes", "zorla durdur", "force stop");

        if (confirm != null && confirm.isEnabled() && clickNode(confirm)) {
            handler.postDelayed(this::openNextAppDetails, 140);
            return;
        }

        attempts++;
        if (attempts <= 5) {
            handler.postDelayed(this::pumpForceStop, 130);
        } else {
            handler.postDelayed(this::openNextAppDetails, 80);
        }
    }

    private void retryOrAdvance() {
        attempts++;
        if (attempts <= 6) {
            handler.postDelayed(this::pumpForceStop, 130);
        } else {
            handler.postDelayed(this::openNextAppDetails, 80);
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
        for (int i = 0; i < 5 && current != null; i++) {
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
        waitingConfirmation = false;
        currentTarget = null;
        performGlobalAction(GLOBAL_ACTION_HOME);
        handler.postDelayed(() -> openRandomFunnyShort(this), 90);
    }

    public static void openRandomFunnyShort(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String last = prefs.getString(KEY_LAST_SHORT, "");

        SecureRandom r = new SecureRandom();
        String selected = FUNNY_SHORT_IDS[r.nextInt(FUNNY_SHORT_IDS.length)];
        if (FUNNY_SHORT_IDS.length > 1) {
            int guard = 0;
            while (selected.equals(last) && guard < 10) {
                selected = FUNNY_SHORT_IDS[r.nextInt(FUNNY_SHORT_IDS.length)];
                guard++;
            }
        }
        prefs.edit().putString(KEY_LAST_SHORT, selected).apply();

        Uri shortUri = Uri.parse("https://www.youtube.com/shorts/" + selected);
        try {
            Intent youtube = new Intent(Intent.ACTION_VIEW, shortUri);
            youtube.setPackage(YOUTUBE_PACKAGE);
            youtube.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(youtube);
            return;
        } catch (Exception ignored) { }

        try {
            Intent web = new Intent(Intent.ACTION_VIEW, shortUri);
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
        if (automationRunning) {
            handler.removeCallbacks(this::pumpForceStop);
            handler.postDelayed(this::pumpForceStop, 40);
        }
    }

    @Override
    public void onInterrupt() { }

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
