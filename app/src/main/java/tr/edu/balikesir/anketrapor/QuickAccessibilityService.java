package tr.edu.balikesir.anketrapor;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.Toast;

public class QuickAccessibilityService extends AccessibilityService {
    private WindowManager windowManager;
    private Button bubble;
    private WindowManager.LayoutParams params;
    private final Handler handler = new Handler(Looper.getMainLooper());

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
                        if (!moved) leaveCurrentAndOpenSahibinden();
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

    private void leaveCurrentAndOpenSahibinden() {
        // Android, normal uygulamalara başka uygulamaları gerçek anlamda force-stop etme izni vermez.
        // En hızlı güvenilir davranış: mevcut uygulamayı arka plana gönderip Sahibinden'i öne almak.
        performGlobalAction(GLOBAL_ACTION_HOME);
        handler.postDelayed(() -> openSahibinden(this), 70);
    }

    public static void openSahibinden(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage("com.sahibinden");
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(launch);
            return;
        }

        try {
            Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.sahibinden"));
            market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(market);
        } catch (Exception e) {
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.sahibinden"));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(web);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Bu yardımcı yalnızca kullanıcı dokunuşuyla çalışır; ekran içeriğini okumaz.
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
