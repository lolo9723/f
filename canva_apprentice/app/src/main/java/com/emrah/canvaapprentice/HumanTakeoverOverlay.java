package com.emrah.canvaapprentice;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class HumanTakeoverOverlay {
    public interface ResumeListener { void onResumeRequested(); }
    private final AccessibilityService service;
    private final WindowManager wm;
    private LinearLayout view;

    public HumanTakeoverOverlay(AccessibilityService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show(String reason, ResumeListener listener) {
        hide();
        LinearLayout box = new LinearLayout(service); box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(20,14,20,14); box.setBackgroundColor(Color.argb(235, 32,32,32));
        TextView text = new TextView(service); text.setTextColor(Color.WHITE); text.setTextSize(14);
        text.setText("Ajan durdu: " + reason + "  ");
        Button resume = new Button(service); resume.setText("DEVAM ET");
        resume.setOnClickListener(v -> { hide(); listener.onResumeRequested(); });
        box.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(resume);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        wm.addView(box, lp); view = box;
    }

    public void hide() {
        if (view != null) { try { wm.removeView(view); } catch (Exception ignored) {} view = null; }
    }
}
