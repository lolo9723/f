package com.emrah.canvaapprentice;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.Deque;

public final class TeacherBridge {
    public interface ReplyCallback { void onReply(String reply); void onFailure(String reason); }
    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String awaitingMarker = "CAA1|";

    public TeacherBridge(AccessibilityService service) {
        this.service = service;
    }

    public void ask(String prompt, ReplyCallback callback) {
        Intent launch = service.getPackageManager().getLaunchIntentForPackage(AgentConstants.CHATGPT_PACKAGE);
        if (launch == null) { callback.onFailure("ChatGPT uygulaması bulunamadı."); return; }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        service.startActivity(launch);
        handler.postDelayed(() -> {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            AccessibilityNodeInfo editor = findEditable(root);
            if (editor == null) { callback.onFailure("ChatGPT mesaj alanı bulunamadı."); return; }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt);
            if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                callback.onFailure("ChatGPT mesajı yazılamadı."); return;
            }
            AccessibilityNodeInfo send = findSend(service.getRootInActiveWindow());
            if (send == null || !clickNodeOrParent(send)) { callback.onFailure("ChatGPT gönder düğmesi bulunamadı."); return; }
            pollReply(callback, 0);
        }, 1000);
    }

    private void pollReply(ReplyCallback callback, int attempt) {
        if (attempt > 45) { callback.onFailure("Öğretmen yanıtı zaman aşımına uğradı."); return; }
        handler.postDelayed(() -> {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            String found = latestTextContaining(root, awaitingMarker);
            if (found != null) callback.onReply(found); else pollReply(callback, attempt + 1);
        }, 1000);
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        if (root == null) return null; AccessibilityNodeInfo last=null;
        Deque<AccessibilityNodeInfo> q=new ArrayDeque<>(); q.add(root);
        while(!q.isEmpty()) { AccessibilityNodeInfo n=q.removeFirst(); if(n.isEditable() && n.isEnabled()) last=n;
            for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i); if(c!=null) q.add(c);} }
        return last;
    }

    private static AccessibilityNodeInfo findSend(AccessibilityNodeInfo root) {
        if (root == null) return null; Deque<AccessibilityNodeInfo> q=new ArrayDeque<>(); q.add(root);
        while(!q.isEmpty()) { AccessibilityNodeInfo n=q.removeFirst(); String s=(text(n.getText())+" "+text(n.getContentDescription())).toLowerCase();
            if ((s.contains("send")||s.contains("gönder")) && n.isEnabled()) return n;
            for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i); if(c!=null) q.add(c);} }
        return null;
    }

    private static String latestTextContaining(AccessibilityNodeInfo root, String marker) {
        if(root==null) return null; String latest=null; Deque<AccessibilityNodeInfo> q=new ArrayDeque<>(); q.add(root);
        while(!q.isEmpty()){ AccessibilityNodeInfo n=q.removeFirst(); String s=text(n.getText()); if(s.contains(marker)) latest=s;
            for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i); if(c!=null) q.add(c);} }
        return latest;
    }

    private static boolean clickNodeOrParent(AccessibilityNodeInfo n){ AccessibilityNodeInfo x=n; while(x!=null&&!x.isClickable()) x=x.getParent(); return x!=null&&x.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
    private static String text(CharSequence s){return s==null?"":s.toString();}
}
