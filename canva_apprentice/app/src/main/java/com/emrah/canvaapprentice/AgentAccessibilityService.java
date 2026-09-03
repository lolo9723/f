package com.emrah.canvaapprentice;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentAccessibilityService extends AccessibilityService {
    public static volatile AgentAccessibilityService INSTANCE;

    private TaskStateRepository repo;
    private SafetyGate safety;
    private ActionExecutor executor;
    private HumanTakeoverOverlay overlay;
    private TeacherBridge teacher;
    private final AtomicBoolean cycleBusy = new AtomicBoolean(false);
    private long lastCycleMs = 0;

    @Override public void onServiceConnected() {
        INSTANCE=this; repo=new TaskStateRepository(this); safety=new SafetyGate(); executor=new ActionExecutor(this);
        overlay=new HumanTakeoverOverlay(this); teacher=new TeacherBridge(this);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (repo == null || event == null) return;
        String pkg = event.getPackageName()==null?"":event.getPackageName().toString();
        if (!AgentConstants.ALLOWED_PACKAGES.contains(pkg)) return;
        TaskState state=repo.load(); if(state.mode!=TaskState.Mode.RUNNING) return;
        long now=System.currentTimeMillis(); if(now-lastCycleMs<700) return; lastCycleMs=now;
        if (pkg.equals(AgentConstants.CANVA_PACKAGE)) runCanvaCycle();
    }

    private void runCanvaCycle() {
        if(!cycleBusy.compareAndSet(false,true)) return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null){cycleBusy.set(false);return;}
        UiTreeSnapshot snap=UiTreeSnapshot.capture(root);
        if(snap.containsSensitiveInput()) {
            pauseForHuman("Şifre / doğrulama alanı algılandı. Gerekli işlemi sen tamamla.");
            cycleBusy.set(false); return;
        }
        TaskState state=repo.load();
        repo.markSafe(snap.stableFingerprint());
        String prompt=TeacherProtocol.buildRequest(state,snap,"Canva ekranını değerlendir ve yalnız bir güvenli sonraki adım ver.");
        teacher.ask(prompt,new TeacherBridge.ReplyCallback(){
            @Override public void onReply(String reply){
                AgentAction action=TeacherProtocol.parse(reply);
                Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
                if(canva!=null){canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);startActivity(canva);}
                getMainExecutor().execute(() -> handleTeacherAction(action));
            }
            @Override public void onFailure(String reason){ pauseForHuman("Öğretmene ulaşılamadı: "+reason); cycleBusy.set(false); }
        });
    }

    private void handleTeacherAction(AgentAction action){
        TaskState state=repo.load();
        if(action.type==AgentAction.Type.HUMAN_TAKEOVER){pauseForHuman(action.reason);cycleBusy.set(false);return;}
        if(action.type==AgentAction.Type.DONE){repo.stop();overlay.hide();cycleBusy.set(false);return;}
        String active=""; AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root!=null&&root.getPackageName()!=null) active=root.getPackageName().toString();
        SafetyGate.Decision d=safety.evaluate(action,state,active);
        if(d.kind==SafetyGate.Decision.Kind.ALLOW){
            boolean ok=executor.execute(action);
            if(!ok) pauseForHuman("Güvenli eylem uygulanamadı; rastgele deneme yapılmadı. "+action.reason);
        } else if(d.kind==SafetyGate.Decision.Kind.ASK_TEACHER) {
            pauseForHuman("Belirsiz/yüksek riskli işlem engellendi: "+d.reason);
        }
        cycleBusy.set(false);
    }

    public void startTask(String goal, boolean allowNewDesign){
        AccessibilityNodeInfo root=getRootInActiveWindow(); String fp="";
        if(root!=null && AgentConstants.CANVA_PACKAGE.equals(String.valueOf(root.getPackageName()))) fp=UiTreeSnapshot.capture(root).stableFingerprint();
        repo.start(goal,allowNewDesign,fp); overlay.hide();
        Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
        if(canva!=null){canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);startActivity(canva);}
    }

    public void stopTask(){repo.stop();overlay.hide();}

    private void pauseForHuman(String reason){repo.pauseForHuman(); overlay.show(reason,()->{repo.resume(); cycleBusy.set(false);});}

    public void captureScreenshotForDiagnostics(ScreenshotCallback cb){
        takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult result){
                HardwareBuffer hb=result.getHardwareBuffer(); Bitmap bmp=Bitmap.wrapHardwareBuffer(hb,result.getColorSpace());
                if(bmp==null){hb.close();cb.onDone(null);return;}
                Bitmap copy=bmp.copy(Bitmap.Config.ARGB_8888,false); hb.close();
                File f=new File(getCacheDir(),"canva_agent_last.png");
                try(FileOutputStream os=new FileOutputStream(f)){copy.compress(Bitmap.CompressFormat.PNG,100,os);cb.onDone(f);}
                catch(Exception e){cb.onDone(null);}
            }
            @Override public void onFailure(int errorCode){cb.onDone(null);}
        });
    }

    public interface ScreenshotCallback{void onDone(File file);}
    @Override public void onInterrupt() {}
    @Override public void onDestroy(){ if(overlay!=null) overlay.hide(); INSTANCE=null; super.onDestroy(); }
}
