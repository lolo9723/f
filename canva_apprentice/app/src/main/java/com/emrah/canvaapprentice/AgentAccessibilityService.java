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
    private ExperienceMemoryRepository memory;
    private final AtomicBoolean cycleBusy = new AtomicBoolean(false);
    private final VisualEvidenceLease visualEvidence = new VisualEvidenceLease();
    private long lastCycleMs = 0;
    private int consecutiveNoVisualChange = 0;
    private int consecutiveExecutionFailures = 0;

    @Override public void onServiceConnected() {
        INSTANCE=this;
        repo=new TaskStateRepository(this);
        safety=new SafetyGate();
        executor=new ActionExecutor(this);
        overlay=new HumanTakeoverOverlay(this);
        teacher=new TeacherBridge(this);
        memory=new ExperienceMemoryRepository(this);

        TaskState restored=repo.load();
        if(restored.mode==TaskState.Mode.HUMAN_TAKEOVER){
            showHumanOverlay(restored.humanReason.isEmpty()?"Kullanıcı işlemi gerekiyor.":restored.humanReason);
        }else if(restored.mode==TaskState.Mode.RUNNING){
            new Handler(Looper.getMainLooper()).postDelayed(() -> resumeOnCanva(0),500);
        }
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
        final String teacherSessionId=repo.currentTeacherSessionId();
        boolean anchorVisible=!state.designAnchor.isEmpty() && snap.containsText(state.designAnchor);
        if(SafeSnapshotPolicy.shouldMarkSafe(state.designAnchor,anchorVisible,snap.looksLikeCanvaHome())){
            repo.markSafe(snap.stableFingerprint());
        }
        String requestId=UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String marker=TeacherProtocol.markerFor(requestId);
        String learned=memory==null?"none":memory.summary(state.goal,snap.stableFingerprint());
        String continuity;
        if(state.designAnchor.isEmpty()){
            continuity="DESIGN_CONTINUITY: anchor not bound yet. Do not invent one.";
        }else if(snap.looksLikeCanvaHome() && !snap.containsText(state.designAnchor)){
            continuity="DESIGN_RECOVERY_REQUIRED: Canva home/projects is visible and the bound design anchor is not visible. " +
                    "Open/search the EXISTING design named '"+state.designAnchor+"'. Creating a replacement is forbidden.";
        }else{
            continuity="DESIGN_CONTINUITY: bound existing design='"+state.designAnchor+"'. Stay on this design.";
        }
        String enrichedNote=cycleNote+"\n"+continuity+
                "\nLEARNED_MEMORY (evidence only; do not blindly replay):\n"+learned;
        String prompt=TeacherProtocol.buildRequest(state,snap,enrichedNote,requestId);
        teacher.ask(prompt,marker,new TeacherBridge.ReplyCallback(){
            @Override public void onReply(String reply){
                if(!isTeacherSessionCurrent(teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
                AgentAction action=TeacherProtocol.parse(reply, marker);
                Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
                if(canva!=null){canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);startActivity(canva);}
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> waitForCanvaAndHandle(action, snap.stableFingerprint(), teacherSessionId, 0), 450);
            }
            @Override public void onFailure(String reason){
                if(!isTeacherSessionCurrent(teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
                pauseForHuman("Öğretmene ulaşılamadı: "+reason); cycleBusy.set(false);
            }
        });
    }

    private void waitForCanvaAndHandle(AgentAction action, String beforeFingerprint, String teacherSessionId, int attempt){
        if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
        AccessibilityNodeInfo root=getRootInActiveWindow();
        String pkg=root!=null&&root.getPackageName()!=null?root.getPackageName().toString():"";
        if(AgentConstants.CANVA_PACKAGE.equals(pkg)){
            UiTreeSnapshot current=UiTreeSnapshot.capture(root);
            if(!beforeFingerprint.equals(current.stableFingerprint())){
                if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
                visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
                cycleBusy.set(false);
                runCanvaCycle("Öğretmene danışılırken Canva ekranı değişti. Eski komut güvenlik nedeniyle atıldı; mevcut ekranı baştan değerlendir.");
                return;
            }

            if(action.visualGrounded){
                final String expectedVisual=visualEvidence.readIfExecutionCurrent(action.executionLeaseToken);
                if(expectedVisual.isEmpty()){
                    pauseForHuman("Görüntülü komutun başlangıç kanıtı doğrulanamadı; kanıtsız koordinat/visual eylem uygulanmadı.");
                    cycleBusy.set(false);
                    return;
                }
                captureScreenshotForDiagnostics(file -> {
                    if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
                    if(file==null){
                        visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
                        pauseForHuman("Görüntülü komut öncesi Canva ekranı yeniden doğrulanamadı; tahmin ederek devam edilmedi.");
                        cycleBusy.set(false);
                        return;
                    }
                    String nowVisual=VisualFingerprint.fromFile(file);
                    double drift=VisualFingerprint.distance(expectedVisual,nowVisual);
                    if(drift>=0.0100){
                        visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
                        cycleBusy.set(false);
                        runCanvaCycle("Görüntülü komut beklerken Canva ekranı görsel olarak değişti (drift="+
                                String.format(java.util.Locale.US,"%.4f",drift)+"). Eski koordinat komutu uygulanmadı.");
                    }else{
                        handleTeacherAction(action,beforeFingerprint,teacherSessionId);
                    }
                });
            }else{
                handleTeacherAction(action,beforeFingerprint,teacherSessionId);
            }
            return;
        }
        if(attempt>=10){
            if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
            pauseForHuman("Canva güvenli biçimde öne getirilemedi; yanlış uygulamada eylem uygulanmadı.");
            cycleBusy.set(false);
            return;
        }
        Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
        if(canva!=null){canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);startActivity(canva);}
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> waitForCanvaAndHandle(action,beforeFingerprint,teacherSessionId,attempt+1),250);
    }

    private void handleTeacherAction(AgentAction action, String beforeFingerprint, String teacherSessionId){
        if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
        TaskState state=repo.load();
        if(action.type==AgentAction.Type.HUMAN_TAKEOVER){
            visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
            pauseForHuman(action.reason);
            cycleBusy.set(false);
            return;
        }
        String active=""; AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root!=null&&root.getPackageName()!=null) active=root.getPackageName().toString();

        if(!AgentConstants.CANVA_PACKAGE.equals(active)){
            pauseForHuman("Eylem öncesi aktif uygulama Canva olarak doğrulanamadı; işlem iptal edildi.");
            cycleBusy.set(false);
            return;
        }

        if(action.type==AgentAction.Type.BIND_DESIGN){
            if(action.confidence<0.98 || !DesignAnchorPolicy.isPlausible(action.target)){
                visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
                pauseForHuman("Tasarım kimliği güvenle bağlanamadı; yanlış tasarıma kilitlenmemek için duruldu.");
                cycleBusy.set(false);
                return;
            }
            repo.bindDesignAnchor(action.target);
            visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
            cycleBusy.set(false);
            runCanvaCycle("Design anchor güvenle bağlandı: '"+action.target+"'. Bundan sonra bu mevcut tasarımda kal.");
            return;
        }

        if(action.type==AgentAction.Type.DONE){
            if(!action.visualGrounded){
                requestVisualTeacher("FINAL QUALITY GATE: Yapısal öğretmen görevin bittiğini düşünüyor. " +
                        "Mevcut Canva tasarımını görsel olarak değerlendir; kullanıcının hedefi gerçekten karşılandıysa DONE de, " +
                        "değilse tek bir güvenli düzeltme ver.");
                return;
            }
            visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
            repo.stop();
            overlay.hide();
            cycleBusy.set(false);
            return;
        }

        if(action.type==AgentAction.Type.NOOP){
            if(action.visualGrounded){
                visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
                pauseForHuman("Görüntülü öğretmen güvenli bir sonraki adım belirleyemedi: "+action.reason);
                cycleBusy.set(false);
            }else{
                requestVisualTeacher("Yapısal öğretmen güvenli eylem bulamadı. Görüntüyü inceleyerek hedefi güvenle belirle: "+action.reason);
            }
            return;
        }

        if(action.type==AgentAction.Type.SCREENSHOT){
            requestVisualTeacher(action.reason);
            return;
        }

        SafetyGate.Decision d=safety.evaluate(action,state,active);
        if(d.kind==SafetyGate.Decision.Kind.ALLOW){
            if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
            boolean ok=executor.execute(action);
            if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
            if(!ok){
                if(memory!=null) memory.record(false,state.goal,beforeFingerprint,action,"");
                if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
                visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
                consecutiveExecutionFailures++;
                cycleBusy.set(false);
                if(consecutiveExecutionFailures>=3){
                    pauseForHuman("Canva öğesi üç kez güvenli biçimde uygulanamadı. Rastgele tıklama yapılmadı.");
                }else{
                    String note="Önceki eylem uygulanamadı ("+action.type+" / "+action.target+"). " +
                            "Aynı hedefi körlemesine tekrarlama; mevcut UI ağacından başka güvenli yol bul.";
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> runCanvaCycleIfActionCurrent(action,teacherSessionId,note),500);
                }
                return;
            }
            consecutiveExecutionFailures=0;
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> verifyActionResult(state,action,beforeFingerprint,teacherSessionId),750);
        } else if(d.kind==SafetyGate.Decision.Kind.ASK_TEACHER) {
            visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
            pauseForHuman("Belirsiz/yüksek riskli işlem engellendi: "+d.reason);
            cycleBusy.set(false);
        } else {
            visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
            cycleBusy.set(false);
        }
    }

    private void verifyActionResult(TaskState state, AgentAction action, String beforeFingerprint, String teacherSessionId){
        if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
        AccessibilityNodeInfo afterRoot=getRootInActiveWindow();
        String afterPkg=afterRoot!=null&&afterRoot.getPackageName()!=null
                ?afterRoot.getPackageName().toString():"";
        if(!AgentConstants.CANVA_PACKAGE.equals(afterPkg)){
            recoverCanvaThenCycle(
                    action,
                    "Önceki eylemden sonra Canva görünür durumda değil. Mevcut tasarıma güvenli biçimde dön; yeni tasarım oluşturma.",
                    teacherSessionId,
                    0
            );
            return;
        }

        UiTreeSnapshot after=UiTreeSnapshot.capture(afterRoot);
        boolean treeChanged=!beforeFingerprint.equals(after.stableFingerprint());

        if(action.visualGrounded){
            final String beforeVisual=visualEvidence.consumeIfExecutionCurrent(action.executionLeaseToken);
            if(beforeVisual.isEmpty()){
                pauseForHuman("Görüntülü eylemin doğrulama kanıtı artık geçerli değil; sonuç varsayılmadı.");
                cycleBusy.set(false);
                return;
            }
            captureScreenshotForDiagnostics(file -> {
                if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
                if(file==null){
                    pauseForHuman("Görüntülü eylem sonrası Canva ekranı doğrulanamadı; sonuç başarılı sayılmadı.");
                    cycleBusy.set(false);
                    return;
                }
                String afterVisual=VisualFingerprint.fromFile(file);
                double visualDistance=VisualFingerprint.distance(beforeVisual,afterVisual);
                if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
                boolean changed=treeChanged || visualDistance>=0.0010;
                finishActionVerification(
                        state,action,beforeFingerprint,after,changed,
                        "visualDistance="+String.format(java.util.Locale.US,"%.4f",visualDistance),teacherSessionId,
                        visualDistance
                );
            });
        }else{
            finishActionVerification(state,action,beforeFingerprint,after,treeChanged,"treeOnly",teacherSessionId,Double.NaN);
        }
    }

    private void finishActionVerification(TaskState state, AgentAction action, String beforeFingerprint,
                                          UiTreeSnapshot after, boolean changed, String evidence, String teacherSessionId,
                                          double visualDistance){
        if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }

        boolean anchorVisible=!state.designAnchor.isEmpty() && after.containsText(state.designAnchor);
        boolean homeVisible=after.looksLikeCanvaHome();
        boolean matchesLastSafe=!state.lastSafeSnapshotHash.isEmpty()
                && state.lastSafeSnapshotHash.equals(after.stableFingerprint());
        boolean visualEditorContinuityVerified=action.visualGrounded
                && DesignContinuityPolicy.visualEditorContinuityFromDistance(visualDistance);
        boolean continuityVerified=DesignContinuityPolicy.verifiesBoundDesignAfterAction(
                state.designAnchor,anchorVisible,homeVisible,matchesLastSafe,visualEditorContinuityVerified);

        if(!continuityVerified){
            if(memory!=null){
                memory.record(false,state.goal,beforeFingerprint,action,"");
            }
            if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
            visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
            cycleBusy.set(false);
            runCanvaCycleIfActionCurrent(
                    action,
                    teacherSessionId,
                    "Önceki eylem UI'ı değiştirdi ancak bağlı mevcut tasarımın içinde kaldığı doğrulanamadı. " +
                    "Bu yol başarı olarak öğrenilmedi. Mevcut tasarım '"+state.designAnchor+
                    "' bağlamını yeniden doğrula; gerekirse yalnız güvenli recovery yap."
            );
            return;
        }

        if(memory!=null){
            memory.record(changed,state.goal,beforeFingerprint,action,changed?after.stableFingerprint():"");
        }
        if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
        if(changed) consecutiveNoVisualChange=0;
        else consecutiveNoVisualChange++;

        cycleBusy.set(false);
        if(consecutiveNoVisualChange>=3){
            pauseForHuman("Üç güvenli denemede Canva ekranında doğrulanabilir değişiklik oluşmadı. Ajan işi bozmak yerine durdu.");
            return;
        }

        String note=changed
                ? "Önceki eylem uygulandı, bağlı tasarım bağlamı korundu ve değişiklik doğrulandı ("+evidence+"). Sonucu değerlendir; gerekiyorsa sonraki tek adımı ver."
                : "Önceki eylem sonrası doğrulanabilir değişiklik görünmedi ("+action.type+" / "+action.target+"; "+evidence+"). " +
                  "Aynı eylemi körlemesine tekrarlama; başka güvenli yol seç veya SCREENSHOT iste.";
        runCanvaCycleIfActionCurrent(action,teacherSessionId,note);
    }

    private void recoverCanvaThenCycle(AgentAction action, String note, String teacherSessionId, int attempt){
        if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
        AccessibilityNodeInfo root=getRootInActiveWindow();
        String pkg=root!=null&&root.getPackageName()!=null?root.getPackageName().toString():"";
        if(AgentConstants.CANVA_PACKAGE.equals(pkg)){
            cycleBusy.set(false);
            runCanvaCycleIfActionCurrent(action,teacherSessionId,note);
            return;
        }
        if(attempt>=10){
            if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
            cycleBusy.set(false);
            pauseForHuman("Canva eylem sonrası yeniden açılamadı. Ajan başka uygulamada işlem yapmadı.");
            return;
        }
        Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
        if(canva!=null){
            canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(canva);
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> recoverCanvaThenCycle(action,note,teacherSessionId,attempt+1),250);
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
        final String teacherSessionId=repo.currentTeacherSessionId();
        final String requestId=UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final String marker=TeacherProtocol.markerFor(requestId);
        final String visualExecutionToken=TeacherExecutionLease.currentGlobalToken();
        captureScreenshotForDiagnostics(file -> {
            if(!isTeacherSessionCurrent(teacherSessionId) || !TeacherExecutionLease.isGlobalCurrent(visualExecutionToken)){
                onStaleTeacherRequestDiscarded();
                return;
            }
            if(file==null){
                pauseForHuman("Canva ekran görüntüsü alınamadı; tahmin ederek devam edilmedi.");
                cycleBusy.set(false);
                return;
            }

            String visualHash=VisualFingerprint.fromFile(file);
            if(!visualEvidence.bindIfExecutionCurrent(visualExecutionToken,visualHash)){
                onStaleTeacherRequestDiscarded();
                return;
            }
            String prompt=TeacherProtocol.buildVisualRequest(state,snap,requestId,screenshotReason);
            teacher.askWithScreenshot(prompt,ScreenshotProvider.uri(),marker,new TeacherBridge.ReplyCallback(){
                @Override public void onReply(String reply){
                    if(!isTeacherSessionCurrent(teacherSessionId) || !TeacherExecutionLease.isGlobalCurrent(visualExecutionToken)){
                        onStaleTeacherRequestDiscarded();
                        return;
                    }
                    AgentAction visualAction=TeacherProtocol.parse(reply,marker,true);
                    if(!visualExecutionToken.equals(visualAction.executionLeaseToken)){
                        onStaleTeacherRequestDiscarded();
                        return;
                    }
                    if(visualAction.type==AgentAction.Type.SCREENSHOT){
                        visualEvidence.clearIfExecutionCurrent(visualExecutionToken);
                        pauseForHuman("Görüntülü öğretmen turu da hedefi güvenle ayıramadı.");
                        cycleBusy.set(false);
                        return;
                    }
                    Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
                    if(canva!=null){
                        canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);startActivity(canva);}
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> waitForCanvaAndHandle(visualAction,snap.stableFingerprint(),teacherSessionId,0),450);
                }

                @Override public void onFailure(String reason){
                    if(!isTeacherSessionCurrent(teacherSessionId) || !TeacherExecutionLease.isGlobalCurrent(visualExecutionToken)){
                        onStaleTeacherRequestDiscarded();
                        return;
                    }
                    visualEvidence.clearIfExecutionCurrent(visualExecutionToken);
                    pauseForHuman("Ekran görüntüsü öğretmene aktarılamadı: "+reason);
                    cycleBusy.set(false);
                }
            });
        });
    }

    public void startTask(String goal, boolean allowNewDesign){
        TeacherExecutionLease.invalidateGlobal();
        visualEvidence.clear();
        cycleBusy.set(false);
        consecutiveNoVisualChange=0;
        consecutiveExecutionFailures=0;
        AccessibilityNodeInfo root=getRootInActiveWindow(); String fp="";
        if(root!=null && AgentConstants.CANVA_PACKAGE.equals(String.valueOf(root.getPackageName()))) fp=UiTreeSnapshot.capture(root).stableFingerprint();
        repo.start(goal,allowNewDesign,fp); overlay.hide();
        Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
        if(canva!=null){canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);startActivity(canva);}
    }

    public void stopTask(){
        TeacherExecutionLease.invalidateGlobal();
        visualEvidence.clear();
        cycleBusy.set(false);
        consecutiveNoVisualChange=0;
        consecutiveExecutionFailures=0;
        repo.stop();
        overlay.hide();
    }

    private void pauseForHuman(String reason){
        TeacherExecutionLease.invalidateGlobal();
        visualEvidence.clear();
        repo.pauseForHuman(reason);
        showHumanOverlay(reason);
    }

    private void showHumanOverlay(String reason){
        overlay.show(reason,()->{
            TeacherExecutionLease.invalidateGlobal();
            visualEvidence.clear();
            repo.resume();
            cycleBusy.set(false);
            consecutiveNoVisualChange=0;
            consecutiveExecutionFailures=0;
            resumeOnCanva(0);
        });
    }

    public void onStaleTeacherRequestDiscarded(){
        // Stale callbacks must be side-effect free: they do not own shared runtime state.
        // The current request/action chain is responsible for clearing its own busy/visual state.
    }

    private boolean isTeacherSessionCurrent(String expectedSessionId){
        if(repo==null) return false;
        TaskState current=repo.load();
        return TeacherSessionPolicy.isCurrent(
                expectedSessionId,
                repo.currentTeacherSessionId(),
                current.mode
        );
    }

    private boolean isActionChainCurrent(AgentAction action, String expectedSessionId){
        return action!=null
                && isTeacherSessionCurrent(expectedSessionId)
                && TeacherExecutionLease.isGlobalCurrent(action.executionLeaseToken);
    }

    private void runCanvaCycleIfActionCurrent(AgentAction action, String teacherSessionId, String note){
        if(!isActionChainCurrent(action,teacherSessionId)){
            onStaleTeacherRequestDiscarded();
            return;
        }
        runCanvaCycle(note);
    }

    private void resumeOnCanva(int attempt){
        TaskState state=repo.load();
        if(state.mode!=TaskState.Mode.RUNNING) return;

        AccessibilityNodeInfo root=getRootInActiveWindow();
        String pkg=root!=null&&root.getPackageName()!=null?root.getPackageName().toString():"";
        if(AgentConstants.CANVA_PACKAGE.equals(pkg)){
            cycleBusy.set(false);
            runCanvaCycle("Kullanıcı müdahalesi tamamlandı. Önce mevcut durumu yeniden doğrula ve kaldığın görevden devam et.");
            return;
        }

        if(attempt>=12){
            repo.pauseForHuman("Canva'ya güvenli biçimde dönülemedi. Canva'yı açıp DEVAM ET'e tekrar bas.");
            showHumanOverlay("Canva'ya güvenli biçimde dönülemedi. Canva'yı açıp DEVAM ET'e tekrar bas.");
            return;
        }

        Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
        if(canva!=null){
            canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(canva);
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> resumeOnCanva(attempt+1),300);
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
    @Override public void onDestroy(){
        TeacherExecutionLease.invalidateGlobal();
        visualEvidence.clear();
        if(overlay!=null) overlay.hide();
        INSTANCE=null;
        super.onDestroy();
    }
}
