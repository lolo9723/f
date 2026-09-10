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
        final ResumeOnceGuard resumeGuard = new ResumeOnceGuard();
        LinearLayout box = new LinearLayout(service); box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(20,14,20,14); box.setBackgroundColor(Color.argb(235, 32,32,32));
        TextView text = new TextView(service); text.setTextColor(Color.WHITE); text.setTextSize(14);
        text.setText("Ajan durdu: " + reason + "  ");
        Button resume = new Button(service); resume.setText("DEVAM ET");
        resume.setOnClickListener(v -> {
            // A fast double tap can enqueue two click callbacks before the overlay is removed.
            // Resume is a state transition and must be one-shot: only the first callback owns it.
            if (!resumeGuard.tryConsume()) return;
            resume.setEnabled(false);
            boolean resumed = ResumeUiTransitionGuard.runSafely(listener::onResumeRequested);
            if (resumed) {
                hide();
                return;
            }
            // A durable resume failure must remain visibly and operationally fail-closed. Do not
            // remove the takeover surface or re-enable DEVAM ET against an uncertain persisted state.
            text.setText("Ajan güvenli olarak durdu: DEVAM ET durumu kalıcılaştırılamadı. " +
                    "Ajan işlem yapmayacak; erişilebilirlik servisini yeniden başlatıp tekrar dene.  ");
            resume.setText("DURDU");
        });
        box.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(resume);
        addOverlay(box);
    }

    /**
     * Shows a non-resumable in-process safety barrier. This is used when a durable state mutation
     * itself failed, so offering DEVAM ET would falsely imply that persisted authority is known.
     */
    public void showHardHold(String reason) {
        hide();
        LinearLayout box = new LinearLayout(service); box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(20,14,20,14); box.setBackgroundColor(Color.argb(235, 32,32,32));
        TextView text = new TextView(service); text.setTextColor(Color.WHITE); text.setTextSize(14);
        text.setText("Ajan güvenli olarak durdu: " + reason + "  ");
        Button stopped = new Button(service); stopped.setText("DURDU"); stopped.setEnabled(false);
        box.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(stopped);
        addOverlay(box);
    }

    private void addOverlay(LinearLayout box) {
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
