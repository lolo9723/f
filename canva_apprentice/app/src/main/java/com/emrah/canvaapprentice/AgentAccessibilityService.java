package com.emrah.canvaapprentice;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.hardware.HardwareBuffer;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

public final class AgentAccessibilityService extends AccessibilityService {
    public static volatile AgentAccessibilityService INSTANCE;

    private TaskStateRepository repo;
    private SafetyGate safety;
    private ActionExecutor executor;
    private HumanTakeoverOverlay overlay;
    private TeacherBridge teacher;
    private final AtomicBoolean cycleBusy = new AtomicBoolean(false);
    private long lastCycleMs = 0;
    private int consecutiveNoVisualChange = 0;
    private int consecutiveExecutionFailures = 0;

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

    private void runCanvaCycle() { runCanvaCycle("Canva ekranını değerlendir ve yalnız bir güvenli sonraki adım ver."); }

    private void runCanvaCycle(String cycleNote) {
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
        String requestId=UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String marker=TeacherProtocol.markerFor(requestId);
        String prompt=TeacherProtocol.buildRequest(state,snap,cycleNote,requestId);
        teacher.ask(prompt,marker,new TeacherBridge.ReplyCallback(){
            @Override public void onReply(String reply){
                AgentAction action=TeacherProtocol.parse(reply, marker);
                Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
                if(canva!=null){canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);startActivity(canva);}
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> waitForCanvaAndHandle(action, snap.stableFingerprint(), 0), 450);
            }
            @Override public void onFailure(String reason){ pauseForHuman("Öğretmene ulaşılamadı: "+reason); cycleBusy.set(false); }
        });
    }

    private void waitForCanvaAndHandle(AgentAction action, String beforeFingerprint, int attempt){
        if(action.type==AgentAction.Type.HUMAN_TAKEOVER || action.type==AgentAction.Type.DONE){
            handleTeacherAction(action,beforeFingerprint);
            return;
        }
        AccessibilityNodeInfo root=getRootInActiveWindow();
        String pkg=root!=null&&root.getPackageName()!=null?root.getPackageName().toString():"";
        if(AgentConstants.CANVA_PACKAGE.equals(pkg)){
            handleTeacherAction(action,beforeFingerprint);
            return;
        }
        if(attempt>=10){
            pauseForHuman("Canva güvenli biçimde öne getirilemedi; yanlış uygulamada eylem uygulanmadı.");
            cycleBusy.set(false);
            return;
        }
        Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
        if(canva!=null){canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);startActivity(canva);}
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> waitForCanvaAndHandle(action,beforeFingerprint,attempt+1),250);
    }

    private void handleTeacherAction(AgentAction action, String beforeFingerprint){
        TaskState state=repo.load();
        if(action.type==AgentAction.Type.HUMAN_TAKEOVER){pauseForHuman(action.reason);cycleBusy.set(false);return;}
        if(action.type==AgentAction.Type.DONE){repo.stop();overlay.hide();cycleBusy.set(false);return;}
        String active=""; AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root!=null&&root.getPackageName()!=null) active=root.getPackageName().toString();

        // Teacher decisions for Canva may never execute on ChatGPT or any other package.
        if(!AgentConstants.CANVA_PACKAGE.equals(active)){
            pauseForHuman("Eylem öncesi aktif uygulama Canva olarak doğrulanamadı; işlem iptal edildi.");
            cycleBusy.set(false);
            return;
        }

        if(action.type==AgentAction.Type.SCREENSHOT){
            requestVisualTeacher(action.reason);
            return;
        }

        SafetyGate.Decision d=safety.evaluate(action,state,active);
        if(d.kind==SafetyGate.Decision.Kind.ALLOW){
            boolean ok=executor.execute(action);
            if(!ok){
                consecutiveExecutionFailures++;
                cycleBusy.set(false);
                if(consecutiveExecutionFailures>=3){
                    pauseForHuman("Canva öğesi üç kez güvenli biçimde uygulanamadı. Rastgele tıklama yapılmadı.");
                }else{
                    String note="Önceki eylem uygulanamadı ("+action.type+" / "+action.target+"). " +
                            "Aynı hedefi körlemesine tekrarlama; mevcut UI ağacından başka güvenli yol bul.";
                    new Handler(Looper.getMainLooper()).postDelayed(() -> runCanvaCycle(note),500);
                }
                return;
            }
            consecutiveExecutionFailures=0;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                AccessibilityNodeInfo afterRoot=getRootInActiveWindow();
                String afterPkg=afterRoot!=null&&afterRoot.getPackageName()!=null?afterRoot.getPackageName().toString():"";
                if(!AgentConstants.CANVA_PACKAGE.equals(afterPkg)){
                    cycleBusy.set(false);
                    runCanvaCycle("Önceki eylemden sonra Canva görünür durumda değil. Önce mevcut tasarıma güvenli biçimde dön; yeni tasarım oluşturma.");
                    return;
                }
                UiTreeSnapshot after=UiTreeSnapshot.capture(afterRoot);
                boolean changed=!beforeFingerprint.equals(after.stableFingerprint());
                if(changed) consecutiveNoVisualChange=0; else consecutiveNoVisualChange++;

                cycleBusy.set(false);
                if(consecutiveNoVisualChange>=3){
                    pauseForHuman("Üç güvenli denemede Canva ekranında doğrulanabilir değişiklik oluşmadı. Ajan işi bozmak yerine durdu.");
                    return;
                }
                String note=changed
                        ? "Önceki eylem uygulandı. Sonucu doğrula ve yalnız gerekliyse bir sonraki adıma geç."
                        : "Önceki eylem sonrası UI ağacında değişiklik görünmedi ("+action.type+" / "+action.target+"). Aynı eylemi körlemesine tekrarlama; alternatif güvenli adım seç.";
                runCanvaCycle(note);
            },750);
        } else if(d.kind==SafetyGate.Decision.Kind.ASK_TEACHER) {
            pauseForHuman("Belirsiz/yüksek riskli işlem engellendi: "+d.reason);
            cycleBusy.set(false);
        } else {
            cycleBusy.set(false);
        }
    }

    private void requestVisualTeacher(String screenshotReason){
        AccessibilityNodeInfo root=getRootInActiveWindow();
        String pkg=root!=null&&root.getPackageName()!=null?root.getPackageName().toString():"";
        if(!AgentConstants.CANVA_PACKAGE.equals(pkg)){
            pauseForHuman("Ekran görüntüsü yalnız Canva açıkken alınabilir.");
            cycleBusy.set(false);
            return;
        }

        UiTreeSnapshot snap=UiTreeSnapshot.capture(root);
        TaskState state=repo.load();
        captureScreenshotForDiagnostics(file -> {
            if(file==null){
                pauseForHuman("Canva ekran görüntüsü alınamadı; tahmin ederek devam edilmedi.");
                cycleBusy.set(false);
                return;
            }

            String requestId=UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String marker=TeacherProtocol.markerFor(requestId);
            String prompt=TeacherProtocol.buildVisualRequest(state,snap,requestId,screenshotReason);
            teacher.askWithScreenshot(prompt,ScreenshotProvider.uri(),marker,new TeacherBridge.ReplyCallback(){
                @Override public void onReply(String reply){
                    AgentAction visualAction=TeacherProtocol.parse(reply,marker);
                    if(visualAction.type==AgentAction.Type.SCREENSHOT){
                        pauseForHuman("Görüntülü öğretmen turu da hedefi güvenle ayıramadı.");
                        cycleBusy.set(false);
                        return;
                    }
                    Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
                    if(canva!=null){
                        canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        startActivity(canva);
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> waitForCanvaAndHandle(visualAction,snap.stableFingerprint(),0),450);
                }

                @Override public void onFailure(String reason){
                    pauseForHuman("Ekran görüntüsü öğretmene aktarılamadı: "+reason);
                    cycleBusy.set(false);
                }
            });
        });
    }

    public void startTask(String goal, boolean allowNewDesign){
        AccessibilityNodeInfo root=getRootInActiveWindow(); String fp="";
        if(root!=null && AgentConstants.CANVA_PACKAGE.equals(String.valueOf(root.getPackageName()))) fp=UiTreeSnapshot.capture(root).stableFingerprint();
        repo.start(goal,allowNewDesign,fp); overlay.hide();
        Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
        if(canva!=null){canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);startActivity(canva);}
    }

    public void stopTask(){repo.stop();overlay.hide();}

    private void pauseForHuman(String reason){
        repo.pauseForHuman();
        overlay.show(reason,()->{
            repo.resume();
            cycleBusy.set(false);
            new Handler(Looper.getMainLooper()).postDelayed(this::runCanvaCycle, 350);
        });
    }

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
