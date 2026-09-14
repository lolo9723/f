package com.emrah.canvaapprentice;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TeacherBridge {
    public interface ReplyCallback {
        void onReply(String reply);
        void onFailure(String reason);
    }

    private static final String STRUCTURAL_HEADER = "CANVA_APPRENTICE_TEACHER_REQUEST";
    private static final String VISUAL_HEADER = "CANVA_APPRENTICE_VISUAL_TEACHER_REQUEST";

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TaskStateRepository stateRepo;
    private volatile String activeRequestToken = "";
    private volatile String activeRequestDesignAnchor = "";

    public TeacherBridge(AccessibilityService service) {
        this.service = service;
        this.stateRepo = new TaskStateRepository(service);
    }

    public void ask(String prompt, TeacherRequestAuthority authority, ReplyCallback callback) {
        final String sessionId = stateRepo.currentTeacherSessionId();
        final String designAnchor = stateRepo.load().designAnchor;
        if (authority == null || !authority.isValid() || !authority.stillOwnsTransport()) {
            callback.onFailure("Yapısal öğretmen için geçerli immutable request authority bulunamadı.");
            return;
        }
        if (!promptMatchesAuthority(prompt, authority, false)) {
            callback.onFailure("Yapısal öğretmen promptu request authority ile birebir eşleşmiyor.");
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
        if (!promptMatchesAuthority(prompt, authority, true)) {
            callback.onFailure("Görüntülü öğretmen promptu request authority ile birebir eşleşmiyor.");
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
                if (!existing.contains(VISUAL_HEADER)) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt);
                    editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                }
            }

            if (!isTransportCurrent(sessionId, requestToken, authority)) {
                discardStaleRequest();
                return;
            }
            AccessibilityNodeInfo beforeSendRoot = service.getRootInActiveWindow();
            Set<String> replyBaseline = visibleReplyTexts(beforeSendRoot, authority.marker);
            int replyBaselineNodeCount = visibleReplyNodeCount(beforeSendRoot, authority.marker);
            Set<String> replyBaselineNodeIdentities = visibleReplyNodeIdentities(beforeSendRoot, authority.marker);
            AccessibilityNodeInfo send = findSend(beforeSendRoot);
            if (send == null || !clickNodeOrParent(send)) {
                failCurrentRequest(sessionId, requestToken, callback, "ChatGPT görüntülü mesaj gönder düğmesi bulunamadı.");
                return;
            }
            pollReply(authority, sessionId, requestToken, callback, 0,
                    replyBaseline, replyBaselineNodeCount, replyBaselineNodeIdentities);
        }, 1400);
    }

    static boolean promptMatchesAuthority(String prompt, TeacherRequestAuthority authority, boolean visual) {
        if (prompt == null || authority == null || !authority.isValid()) return false;
        if (visual != authority.isVisualGrounded()) return false;
        String expectedHeader = visual ? VISUAL_HEADER : STRUCTURAL_HEADER;
        if (!prompt.startsWith(expectedHeader + "\n")) return false;

        String expectedRequestLine = "RequestId: " + authority.requestId;
        int requestLines = 0;
        for (String line : prompt.split("\\R", -1)) {
            if (line.startsWith("RequestId:")) {
                requestLines++;
                if (!expectedRequestLine.equals(line)) return false;
            }
        }
        return requestLines == 1;
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
        AccessibilityNodeInfo beforeSendRoot = service.getRootInActiveWindow();
        Set<String> replyBaseline = visibleReplyTexts(beforeSendRoot, authority.marker);
        int replyBaselineNodeCount = visibleReplyNodeCount(beforeSendRoot, authority.marker);
        Set<String> replyBaselineNodeIdentities = visibleReplyNodeIdentities(beforeSendRoot, authority.marker);
        AccessibilityNodeInfo send = findSend(beforeSendRoot);
        if (send == null || !clickNodeOrParent(send)) {
            failCurrentRequest(sessionId, requestToken, callback, "ChatGPT gönder düğmesi bulunamadı.");
            return;
        }
        pollReply(authority, sessionId, requestToken, callback, 0,
                replyBaseline, replyBaselineNodeCount, replyBaselineNodeIdentities);
    }

    private void pollReply(TeacherRequestAuthority authority, String sessionId, String requestToken,
                           ReplyCallback callback, int attempt, Set<String> replyBaseline,
                           int replyBaselineNodeCount, Set<String> replyBaselineNodeIdentities) {
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
                pollReply(authority, sessionId, requestToken, callback, attempt + 1,
                        replyBaseline, replyBaselineNodeCount, replyBaselineNodeIdentities);
                return;
            }
            String found = latestTextContaining(root, authority.marker,
                    replyBaseline, replyBaselineNodeCount, replyBaselineNodeIdentities);
            if (found != null) {
                if (!isTransportCurrent(sessionId, requestToken, authority)
                        || !consumeIfCurrent(sessionId, requestToken)) {
                    discardStaleRequest();
                    return;
                }
                callback.onReply(found);
            } else {
                pollReply(authority, sessionId, requestToken, callback, attempt + 1,
                        replyBaseline, replyBaselineNodeCount, replyBaselineNodeIdentities);
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
            if (TeacherUiPolicy.isUsableEditable(n.isVisibleToUser(), n.isEnabled(), n.isEditable())) last = n;
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
            if (TeacherUiPolicy.isUsableSend(n.isVisibleToUser(), n.isEnabled(), label, description)) {
                return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }

    private static Set<String> visibleReplyTexts(AccessibilityNodeInfo root, String marker) {
        Set<String> replies = new HashSet<>();
        if (root == null) return replies;
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String value = text(n.getText());
            if (isEligibleReplyNode(n.isVisibleToUser(), value, marker, null)) replies.add(value);
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return replies;
    }

    private static int visibleReplyNodeCount(AccessibilityNodeInfo root, String marker) {
        if (root == null) return 0;
        int count = 0;
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            if (n.isVisibleToUser() && hasReplyLine(text(n.getText()), marker)) count++;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return count;
    }

    private static Set<String> visibleReplyNodeIdentities(AccessibilityNodeInfo root, String marker) {
        Set<String> identities = new HashSet<>();
        if (root == null) return identities;
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            if (n.isVisibleToUser() && hasReplyLine(text(n.getText()), marker)) {
                String identity = stableNodeIdentity(n);
                if (!identity.isEmpty()) identities.add(identity);
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return identities;
    }

    private static Map<String, Integer> visibleReplyNodeIdentityCounts(AccessibilityNodeInfo root, String marker) {
        Map<String, Integer> counts = new HashMap<>();
        if (root == null) return counts;
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            if (n.isVisibleToUser() && hasReplyLine(text(n.getText()), marker)) {
                String identity = stableNodeIdentity(n);
                if (!identity.isEmpty()) counts.put(identity, counts.getOrDefault(identity, 0) + 1);
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return counts;
    }

    private static String latestTextContaining(AccessibilityNodeInfo root, String marker,
                                               Set<String> replyBaseline,
                                               int replyBaselineNodeCount,
                                               Set<String> replyBaselineNodeIdentities) {
        if (root == null) return null;
        String latest = null;
        int markerOccurrence = 0;
        Map<String, Integer> currentIdentityCounts = visibleReplyNodeIdentityCounts(root, marker);
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String s = text(n.getText());
            if (n.isVisibleToUser() && hasReplyLine(s, marker)) {
                String nodeIdentity = stableNodeIdentity(n);
                int identityOccurrenceCount = nodeIdentity.isEmpty()
                        ? 0 : currentIdentityCounts.getOrDefault(nodeIdentity, 0);
                if (isEligiblePostDispatchReplyNode(
                        true, s, marker, replyBaseline,
                        markerOccurrence, replyBaselineNodeCount,
                        nodeIdentity, replyBaselineNodeIdentities, identityOccurrenceCount)) {
                    latest = s;
                }
                markerOccurrence++;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return latest;
    }

    static boolean isEligibleReplyNode(boolean visibleToUser, String value, String marker) {
        return isEligibleReplyNode(visibleToUser, value, marker, null);
    }

    static boolean isEligibleReplyNode(boolean visibleToUser, String value, String marker,
                                       Set<String> replyBaseline) {
        if (!visibleToUser || !hasReplyLine(value, marker)) return false;
        return replyBaseline == null || !replyBaseline.contains(value);
    }

    static boolean isEligiblePostDispatchReplyNode(boolean visibleToUser, String value, String marker,
                                                   Set<String> replyBaseline,
                                                   int markerOccurrence,
                                                   int replyBaselineNodeCount) {
        return isEligiblePostDispatchReplyNode(
                visibleToUser, value, marker, replyBaseline,
                markerOccurrence, replyBaselineNodeCount, "", null, 0);
    }

    static boolean isEligiblePostDispatchReplyNode(boolean visibleToUser, String value, String marker,
                                                   Set<String> replyBaseline,
                                                   int markerOccurrence,
                                                   int replyBaselineNodeCount,
                                                   String stableNodeIdentity,
                                                   Set<String> replyBaselineNodeIdentities) {
        return isEligiblePostDispatchReplyNode(
                visibleToUser, value, marker, replyBaseline,
                markerOccurrence, replyBaselineNodeCount,
                stableNodeIdentity, replyBaselineNodeIdentities,
                stableNodeIdentity == null || stableNodeIdentity.isEmpty() ? 0 : 1);
    }

    static boolean isEligiblePostDispatchReplyNode(boolean visibleToUser, String value, String marker,
                                                   Set<String> replyBaseline,
                                                   int markerOccurrence,
                                                   int replyBaselineNodeCount,
                                                   String stableNodeIdentity,
                                                   Set<String> replyBaselineNodeIdentities,
                                                   int currentStableIdentityCount) {
        if (!isEligibleReplyNode(visibleToUser, value, marker, replyBaseline)) return false;
        if (markerOccurrence < 0 || replyBaselineNodeCount < 0 || currentStableIdentityCount < 0) return false;
        if (stableNodeIdentity != null && !stableNodeIdentity.isEmpty()) {
            // Stable identity only proves provenance when exactly one currently-visible reply node
            // owns it. Shared ancestry fingerprints (or duplicated provider uniqueIds) are
            // ambiguous, so occurrence order must never be allowed to override that ambiguity.
            if (currentStableIdentityCount != 1) return false;
            if (replyBaselineNodeIdentities != null
                    && replyBaselineNodeIdentities.contains(stableNodeIdentity)) {
                return false;
            }
        }
        // Occurrence order is the final conservative fallback for providers that expose no stable
        // identity at all. Once an identity exists, ambiguity is handled above and fails closed.
        return markerOccurrence >= replyBaselineNodeCount;
    }

    private static String stableNodeIdentity(AccessibilityNodeInfo node) {
        if (node == null) return "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String uniqueId = text(node.getUniqueId());
            if (!uniqueId.isEmpty()) return "uid:" + uniqueId;
        }

        String[] ancestry = new String[12];
        int partCount = 0;
        AccessibilityNodeInfo current = node;
        while (current != null && partCount < ancestry.length) {
            ancestry[partCount++] = text(current.getClassName());
            ancestry[partCount++] = text(current.getViewIdResourceName());
            current = current.getParent();
        }
        if (partCount < 4) return "";
        String[] compact = new String[partCount];
        System.arraycopy(ancestry, 0, compact, 0, partCount);
        return composeStructuralAncestryIdentity(node.getWindowId(), compact);
    }

    static String composeStructuralAncestryIdentity(int windowId, String... classAndViewIdPairs) {
        if (windowId < 0 || classAndViewIdPairs == null
                || classAndViewIdPairs.length < 4
                || classAndViewIdPairs.length % 2 != 0) {
            return "";
        }
        StringBuilder out = new StringBuilder("anc:w").append(windowId);
        for (int i = 0; i < classAndViewIdPairs.length; i += 2) {
            String className = classAndViewIdPairs[i] == null ? "" : classAndViewIdPairs[i];
            String viewId = classAndViewIdPairs[i + 1] == null ? "" : classAndViewIdPairs[i + 1];
            if (className.isEmpty() && viewId.isEmpty()) return "";
            out.append('|').append(className.length()).append(':').append(className)
                    .append('|').append(viewId.length()).append(':').append(viewId);
        }
        return out.toString();
    }

    static boolean hasReplyLine(String value, String marker) {
        if (value == null || marker == null || marker.isEmpty()) return false;
        for (String line : value.split("\\R", -1)) {
            // Protocol authority must begin at physical column zero. Trimming here would
            // accept indented/quoted/rendered explanatory text as an executable reply.
            if (line.startsWith(marker)) return true;
        }
        return false;
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
