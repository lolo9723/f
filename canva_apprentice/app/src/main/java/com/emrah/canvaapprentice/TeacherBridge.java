package com.emrah.canvaapprentice;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.Deque;

public final class TeacherBridge {
    public interface ReplyCallback {
        void onReply(String reply);
        void onFailure(String reason);
    }

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public TeacherBridge(AccessibilityService service) {
        this.service = service;
    }

    public void ask(String prompt, String awaitingMarker, ReplyCallback callback) {
        Intent launch = service.getPackageManager().getLaunchIntentForPackage(AgentConstants.CHATGPT_PACKAGE);
        if (launch == null) {
            callback.onFailure("ChatGPT uygulaması bulunamadı.");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        service.startActivity(launch);
        handler.postDelayed(() -> submitPromptOnCurrentChat(prompt, awaitingMarker, callback), 1000);
    }

    public void askWithScreenshot(String prompt, Uri screenshotUri, String awaitingMarker, ReplyCallback callback) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setPackage(AgentConstants.CHATGPT_PACKAGE);
        share.setType("image/png");
        share.putExtra(Intent.EXTRA_STREAM, screenshotUri);
        share.putExtra(Intent.EXTRA_TEXT, prompt);
        share.setClipData(ClipData.newRawUri("Canva screenshot", screenshotUri));
        share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
                Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (share.resolveActivity(service.getPackageManager()) == null) {
            callback.onFailure("ChatGPT görüntü paylaşım hedefi bulunamadı.");
            return;
        }

        service.startActivity(share);
        handler.postDelayed(() -> {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (!AgentConstants.CHATGPT_PACKAGE.equals(packageOf(root))) {
                callback.onFailure("Görüntü ChatGPT'ye güvenli biçimde açılamadı.");
                return;
            }

            // Some Android share targets prefill EXTRA_TEXT, some do not.
            AccessibilityNodeInfo editor = findEditable(root);
            if (editor != null) {
                String existing = text(editor.getText());
                if (!existing.contains("CANVA_APPRENTICE_VISUAL_TEACHER_REQUEST")) {
                    Bundle args = new Bundle();
                    args.putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            prompt
                    );
                    editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                }
            }

            AccessibilityNodeInfo send = findSend(service.getRootInActiveWindow());
            if (send == null || !clickNodeOrParent(send)) {
                callback.onFailure("ChatGPT görüntülü mesaj gönder düğmesi bulunamadı.");
                return;
            }
            pollReply(awaitingMarker, callback, 0);
        }, 1400);
    }

    private void submitPromptOnCurrentChat(String prompt, String awaitingMarker, ReplyCallback callback) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (!AgentConstants.CHATGPT_PACKAGE.equals(packageOf(root))) {
            callback.onFailure("ChatGPT aktif pencere olarak doğrulanamadı.");
            return;
        }

        AccessibilityNodeInfo editor = findEditable(root);
        if (editor == null) {
            callback.onFailure("ChatGPT mesaj alanı bulunamadı.");
            return;
        }

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt);
        if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            callback.onFailure("ChatGPT mesajı yazılamadı.");
            return;
        }

        AccessibilityNodeInfo send = findSend(service.getRootInActiveWindow());
        if (send == null || !clickNodeOrParent(send)) {
            callback.onFailure("ChatGPT gönder düğmesi bulunamadı.");
            return;
        }
        pollReply(awaitingMarker, callback, 0);
    }

    private void pollReply(String awaitingMarker, ReplyCallback callback, int attempt) {
        if (attempt > 60) {
            callback.onFailure("Öğretmen yanıtı zaman aşımına uğradı.");
            return;
        }
        handler.postDelayed(() -> {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (!AgentConstants.CHATGPT_PACKAGE.equals(packageOf(root))) {
                pollReply(awaitingMarker, callback, attempt + 1);
                return;
            }
            String found = latestTextContaining(root, awaitingMarker);
            if (found != null) callback.onReply(found);
            else pollReply(awaitingMarker, callback, attempt + 1);
        }, 1000);
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo last = null;
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            if (n.isEditable() && n.isEnabled()) last = n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return last;
    }

    private static AccessibilityNodeInfo findSend(AccessibilityNodeInfo root) {
        if (root == null) return null;
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String label = text(n.getText());
            String description = text(n.getContentDescription());
            if (n.isEnabled() &&
                    (TeacherUiPolicy.isExactSendLabel(label) || TeacherUiPolicy.isExactSendLabel(description))) {
                return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }

    private static String latestTextContaining(AccessibilityNodeInfo root, String marker) {
        if (root == null) return null;
        String latest = null;
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String s = text(n.getText());
            if (s.contains(marker)) latest = s;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return latest;
    }

    private static String packageOf(AccessibilityNodeInfo root) {
        return root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
    }

    private static boolean clickNodeOrParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo x = n;
        while (x != null && !x.isClickable()) x = x.getParent();
        return x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static String text(CharSequence s) {
        return s == null ? "" : s.toString();
    }
}
