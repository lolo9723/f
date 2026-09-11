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
import java.util.UUID;

public final class TeacherBridge {
    public interface ReplyCallback {
        void onReply(String reply);
        void onFailure(String reason);
    }

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TaskStateRepository stateRepo;
    private volatile String activeRequestToken = "";
    private volatile String activeRequestDesignAnchor = "";

    public TeacherBridge(AccessibilityService service) {
        this.service = service;
        this.stateRepo = new TaskStateRepository(service);
    }

    public void ask(String prompt, String awaitingMarker, ReplyCallback callback) {
        ask(prompt, TeacherRequestAuthority.fromBoundStructural(awaitingMarker), callback);
    }

    public void ask(String prompt, TeacherRequestAuthority authority, ReplyCallback callback) {
        final String sessionId = stateRepo.currentTeacherSessionId();
        final String designAnchor = stateRepo.load().designAnchor;
        if (authority == null || !authority.isValid() || !authority.stillOwnsTransport()) {
            callback.onFailure("Yapısal öğretmen için geçerli immutable request authority bulunamadı.");
            return;
        }
        final String requestToken = beginRequest(designAnchor);
        Intent launch = service.getPackageManager().getLaunchIntentForPackage(AgentConstants.CHATGPT_PACKAGE);
        if (launch == null) {
            failCurrentRequest(sessionId, requestToken, callback, "ChatGPT uygulaması bulunamadı.");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        service.startActivity(launch);
        handler.postDelayed(() -> submitPromptOnCurrentChat(
                prompt, authority, sessionId, requestToken, callback), 1000);
    }

    public void askWithScreenshot(String prompt, Uri screenshotUri, TeacherRequestAuthority authority,
                                  ReplyCallback callback) {
        final String sessionId = stateRepo.currentTeacherSessionId();
        final String designAnchor = stateRepo.load().designAnchor;
        if (authority == null || !authority.isValid() || !authority.stillOwnsTransport()) {
            callback.onFailure("Görüntülü öğretmen için geçerli request authority bulunamadı.");
            return;
        }
        final String requestToken = beginRequest(designAnchor);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setPackage(AgentConstants.CHATGPT_PACKAGE);
        share.setType("image/png");
        share.putExtra(Intent.EXTRA_STREAM, screenshotUri);
        share.putExtra(Intent.EXTRA_TEXT, prompt);
        share.setClipData(ClipData.newRawUri("Canva screenshot", screenshotUri));
        share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
                Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (share.resolveActivity(service.getPackageManager()) == null) {
            failCurrentRequest(sessionId, requestToken, callback, "ChatGPT görüntü paylaşım hedefi bulunamadı.");
            return;
        }

        service.startActivity(share);
        handler.postDelayed(() -> {
            if (!isTransportCurrent(sessionId, requestToken, authority)) {
                discardStaleRequest();
                return;
            }
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (!AgentConstants.CHATGPT_PACKAGE.equals(packageOf(root))) {
                failCurrentRequest(sessionId, requestToken, callback, "Görüntü ChatGPT'ye güvenli biçimde açılamadı.");
                return;
            }

            AccessibilityNodeInfo editor = findEditable(root);
            if (editor != null) {
                String existing = text(editor.getText());
                if (!existing.contains("CANVA_APPRENTICE_VISUAL_TEACHER_REQUEST")) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt);
                    editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                }
            }

            if (!isTransportCurrent(sessionId, requestToken, authority)) {
                discardStaleRequest();
                return;
            }
            AccessibilityNodeInfo send = findSend(service.getRootInActiveWindow());
            if (send == null || !clickNodeOrParent(send)) {
                failCurrentRequest(sessionId, requestToken, callback, "ChatGPT görüntülü mesaj gönder düğmesi bulunamadı.");
                return;
            }
            pollReply(authority, sessionId, requestToken, callback, 0);
        }, 1400);
    }

    private void submitPromptOnCurrentChat(String prompt, TeacherRequestAuthority authority,
                                           String sessionId, String requestToken,
                                           ReplyCallback callback) {
        if (!isTransportCurrent(sessionId, requestToken, authority)) {
            discardStaleRequest();
            return;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (!AgentConstants.CHATGPT_PACKAGE.equals(packageOf(root))) {
            failCurrentRequest(sessionId, requestToken, callback, "ChatGPT aktif pencere olarak doğrulanamadı.");
            return;
        }

        AccessibilityNodeInfo editor = findEditable(root);
        if (editor == null) {
            failCurrentRequest(sessionId, requestToken, callback, "ChatGPT mesaj alanı bulunamadı.");
            return;
        }

        if (!isTransportCurrent(sessionId, requestToken, authority)) {
            discardStaleRequest();
            return;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt);
        if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            failCurrentRequest(sessionId, requestToken, callback, "ChatGPT mesajı yazılamadı.");
            return;
        }

        if (!isTransportCurrent(sessionId, requestToken, authority)) {
            discardStaleRequest();
            return;
        }
        AccessibilityNodeInfo send = findSend(service.getRootInActiveWindow());
        if (send == null || !clickNodeOrParent(send)) {
            failCurrentRequest(sessionId, requestToken, callback, "ChatGPT gönder düğmesi bulunamadı.");
            return;
        }
        pollReply(authority, sessionId, requestToken, callback, 0);
    }

    private void pollReply(TeacherRequestAuthority authority, String sessionId, String requestToken,
                           ReplyCallback callback, int attempt) {
        if (!isTransportCurrent(sessionId, requestToken, authority)) {
            discardStaleRequest();
            return;
        }
        if (attempt > 60) {
            failCurrentRequest(sessionId, requestToken, callback, "Öğretmen yanıtı zaman aşımına uğradı.");
            return;
        }
        handler.postDelayed(() -> {
            if (!isTransportCurrent(sessionId, requestToken, authority)) {
                discardStaleRequest();
                return;
            }
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (!AgentConstants.CHATGPT_PACKAGE.equals(packageOf(root))) {
                pollReply(authority, sessionId, requestToken, callback, attempt + 1);
                return;
            }
            String found = latestTextContaining(root, authority.marker);
            if (found != null) {
                if (!isTransportCurrent(sessionId, requestToken, authority)
                        || !consumeIfCurrent(sessionId, requestToken)) {
                    discardStaleRequest();
                    return;
                }
                callback.onReply(found);
            } else {
                pollReply(authority, sessionId, requestToken, callback, attempt + 1);
            }
        }, 1000);
    }

    private synchronized String beginRequest(String designAnchor) {
        activeRequestToken = UUID.randomUUID().toString();
        activeRequestDesignAnchor = designAnchor == null ? "" : designAnchor;
        return activeRequestToken;
    }

    private boolean isRequestCurrent(String expectedSessionId, String requestToken) {
        TaskState state = stateRepo.load();
        String activeToken = activeRequestToken;
        String requestDesignAnchor = activeRequestDesignAnchor;
        return TeacherRequestPolicy.isCurrent(
                expectedSessionId,
                stateRepo.currentTeacherSessionId(),
                state.mode,
                requestToken,
                activeToken,
                requestDesignAnchor,
                state.designAnchor
        );
    }

    private boolean isTransportCurrent(String expectedSessionId, String requestToken,
                                       TeacherRequestAuthority authority) {
        return isRequestCurrent(expectedSessionId, requestToken)
                && authority != null
                && authority.stillOwnsTransport();
    }

    private synchronized boolean consumeIfCurrent(String expectedSessionId, String requestToken) {
        if (!isRequestCurrent(expectedSessionId, requestToken)) return false;
        activeRequestToken = "";
        activeRequestDesignAnchor = "";
        return true;
    }

    private void failCurrentRequest(String expectedSessionId, String requestToken,
                                    ReplyCallback callback, String reason) {
        if (!consumeIfCurrent(expectedSessionId, requestToken)) {
            discardStaleRequest();
            return;
        }
        callback.onFailure(reason);
    }

    private void discardStaleRequest() {
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

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
