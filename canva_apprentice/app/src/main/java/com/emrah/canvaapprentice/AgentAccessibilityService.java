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
    private final AtomicBoolean persistenceHardHold = new AtomicBoolean(false);
    private final VisualEvidenceLease visualEvidence = new VisualEvidenceLease();
    private final ResumeGenerationGuard resumeGeneration = new ResumeGenerationGuard();
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
        ScreenshotProvider.cleanupExpiredEvidence(getCacheDir(), System.currentTimeMillis());

        TaskState restored=repo.load();
        if(restored.mode==TaskState.Mode.HUMAN_TAKEOVER){
            showHumanOverlay(restored.humanReason.isEmpty()?"Kullanıcı işlemi gerekiyor.":restored.humanReason);
        }else if(restored.mode==TaskState.Mode.RUNNING){
            final long generation=resumeGeneration.begin();
            final String resumeSessionId=repo.currentTeacherSessionId();
            final String resumeDesignAnchor=restored.designAnchor;
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> resumeOnCanva(generation,resumeSessionId,resumeDesignAnchor,0),500);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (repo == null || event == null || persistenceHardHold.get()) return;
        String pkg = event.getPackageName()==null?"":event.getPackageName().toString();
        if (!AgentConstants.ALLOWED_PACKAGES.contains(pkg)) return;
        TaskState state=repo.load(); if(state.mode!=TaskState.Mode.RUNNING) return;
        long now=System.currentTimeMillis(); if(now-lastCycleMs<700) return; lastCycleMs=now;
        if (pkg.equals(AgentConstants.CANVA_PACKAGE)) runCanvaCycle();
    }

    private void runCanvaCycle() { runCanvaCycle("Canva ekranını değerlendir ve yalnız bir güvenli sonraki adım ver."); }

    private void runCanvaCycle(String cycleNote) {
        if(persistenceHardHold.get()) return;
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
        final TeacherRequestAuthority structuralAuthority=TeacherRequestAuthority.begin(
                requestId,snap.stableFingerprint());
        if(!structuralAuthority.isValid() || !structuralAuthority.stillOwnsTransport()){
            pauseForHuman("Yapısal öğretmen için güvenli request authority oluşturulamadı; komut alınmadan duruldu.");
            cycleBusy.set(false);
            return;
        }
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
        String prompt=TeacherProtocol.buildRequest(state,snap,enrichedNote,structuralAuthority);
        teacher.ask(prompt,structuralAuthority,new TeacherBridge.ReplyCallback(){
            @Override public void onReply(String reply){
                if(!isTeacherSessionCurrent(teacherSessionId) || !structuralAuthority.stillOwnsTransport()){
                    onStaleTeacherRequestDiscarded();
                    return;
                }
                AgentAction action=TeacherProtocol.parse(reply, structuralAuthority);
                if(!structuralAuthority.executionLeaseToken.equals(action.executionLeaseToken)){
                    onStaleTeacherRequestDiscarded();
                    return;
                }
                Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
                if(canva!=null){canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);startActivity(canva);}
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> waitForCanvaAndHandle(action, structuralAuthority.snapshotFingerprint, teacherSessionId, 0), 450);
            }
            @Override public void onFailure(String reason){
                if(!isTeacherSessionCurrent(teacherSessionId) || !structuralAuthority.stillOwnsTransport()){
                    onStaleTeacherRequestDiscarded();
                    return;
                }
                pauseForHuman("Öğretmene ulaşılamadı: "+reason); cycleBusy.set(false);
            }
        });
    }

    private void waitForCanvaAndHandle(AgentAction action, String beforeFingerprint, String teacherSessionId, int attempt){
        if(persistenceHardHold.get()) return;
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
                    if(persistenceHardHold.get()) return;
                    if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }
                    if(file==null){
                        visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
                        pauseForHuman("Görüntülü komut öncesi Canva ekranı yeniden doğrulanamadı; tahmin ederek devam edilmedi.");
                        cycleBusy.set(false);
                        return;
                    }
                    String nowVisual=VisualFingerprint.fromFile(file);
                    double drift=VisualFingerprint.distance(expectedVisual,nowVisual);
                    boolean executionContextMatches=VisualRequestContextGuard.currentExecutionAllows(drift,0.0100);
                    if(!executionContextMatches){
                        visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
                        cycleBusy.set(false);
                        runCanvaCycle("Görüntülü komut beklerken Canva ekranı/bağlı tasarım execution bağlamı değişti veya görsel drift sınırı aşıldı (drift="+
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
        if(persistenceHardHold.get()) return;
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

        UiTreeSnapshot preAction=UiTreeSnapshot.capture(root);
        boolean preAnchorVisible=!state.designAnchor.isEmpty() && preAction.containsText(state.designAnchor);
        boolean preMatchesLastSafe=!state.lastSafeSnapshotHash.isEmpty()
                && state.lastSafeSnapshotHash.equals(preAction.stableFingerprint());
        boolean preActionBoundDesignVerified=DesignContinuityPolicy.preActionBoundDesignVerified(
                state.designAnchor,preAnchorVisible,preAction.looksLikeCanvaHome(),preMatchesLastSafe);

        if(action.type==AgentAction.Type.BIND_DESIGN){
            boolean exactTargetVisible=preAction.containsText(action.target);
            boolean mayBind=action.confidence>=0.98
                    && DesignAnchorPolicy.mayBindVisibleEditor(
                            action.target,exactTargetVisible,preAction.looksLikeCanvaHome());
            if(!mayBind || !isActionChainCurrent(action,teacherSessionId)){
                visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
                pauseForHuman("Tasarım kimliği canlı Canva editöründe bağımsız olarak doğrulanamadı; yanlış tasarıma kilitlenmemek için duruldu.");
                cycleBusy.set(false);
                return;
            }
            boolean bindCommitted=repo.bindDesignAnchor(action.target,teacherSessionId);
            TaskState bound=repo.load();
            if(!bindCommitted || !isActionChainCurrent(action,teacherSessionId) || !action.target.equals(bound.designAnchor)){
                visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
                pauseForHuman("Tasarım kimliği kalıcı duruma güvenle bağlanamadı; ajan devam etmedi.");
                cycleBusy.set(false);
                return;
            }
            visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
            cycleBusy.set(false);
            runCanvaCycleIfActionCurrent(
                    action,
                    teacherSessionId,
                    "Design anchor canlı editör kanıtıyla güvenle bağlandı: '"+action.target+"'. Bundan sonra bu mevcut tasarımda kal."
            );
            return;
        }

        if(action.type==AgentAction.Type.DONE){
            if(!action.visualGrounded){
                requestVisualTeacher("FINAL QUALITY GATE: Yapısal öğretmen görevin bittiğini düşünüyor. " +
                        "Mevcut Canva tasarımını görsel olarak değerlendir; kullanıcının hedefi gerçekten karşılandıysa DONE de, " +
                        "değilse tek bir güvenli düzeltme ver.");
                return;
            }
            boolean leaseOwnedVisualEvidence=!visualEvidence.readIfExecutionCurrent(action.executionLeaseToken).isEmpty();
            boolean finalDoneVerified=DesignContinuityPolicy.finalDoneMayStop(
                    state.designAnchor,action.visualGrounded,preActionBoundDesignVerified,leaseOwnedVisualEvidence);
            if(!finalDoneVerified){
                visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
                cycleBusy.set(false);
                runCanvaCycleIfActionCurrent(
                        action,
                        teacherSessionId,
                        "FINAL QUALITY GATE reddedildi: Görsel DONE yanıtı bağlı mevcut tasarım kimliği ve current-lease ekran kanıtı ile " +
                        "bağımsız olarak doğrulanamadı. Görevi bitirme; önce aynı mevcut tasarım bağlamını yeniden kanıtla."
                );
                return;
            }
            boolean stopCommitted=FinalDoneCommitGuard.commitIfCurrent(
                    action.executionLeaseToken,
                    () -> isTeacherSessionCurrent(teacherSessionId),
                    repo::stop
            );
            if(!stopCommitted){
                enterPersistenceHardHold("Görevi bitirme durumu kalıcılaştırılamadı. Servisi yeniden başlatmadan ajan devam etmeyecek.");
                return;
            }
            visualEvidence.clear();
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
                    () -> verifyActionResult(state,action,beforeFingerprint,teacherSessionId,preActionBoundDesignVerified),750);
        } else if(d.kind==SafetyGate.Decision.Kind.ASK_TEACHER) {
            visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
            pauseForHuman("Belirsiz/yüksek riskli işlem engellendi: "+d.reason);
            cycleBusy.set(false);
        } else {
            visualEvidence.clearIfExecutionCurrent(action.executionLeaseToken);
            cycleBusy.set(false);
        }
    }

    private void verifyActionResult(TaskState state, AgentAction action, String beforeFingerprint, String teacherSessionId,
                                    boolean preActionBoundDesignVerified){
        if(persistenceHardHold.get()) return;
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
            final boolean leaseOwnedVisualEvidence=true;
            captureScreenshotForDiagnostics(file -> {
                if(persistenceHardHold.get()) return;
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
                        visualDistance,preActionBoundDesignVerified,leaseOwnedVisualEvidence
                );
            });
        }else{
            finishActionVerification(
                    state,action,beforeFingerprint,after,treeChanged,"treeOnly",teacherSessionId,Double.NaN,
                    preActionBoundDesignVerified,false
            );
        }
    }

    private void finishActionVerification(TaskState state, AgentAction action, String beforeFingerprint,
                                          UiTreeSnapshot after, boolean changed, String evidence, String teacherSessionId,
                                          double visualDistance, boolean preActionBoundDesignVerified,
                                          boolean leaseOwnedVisualEvidence){
        if(persistenceHardHold.get()) return;
        if(!isActionChainCurrent(action,teacherSessionId)){ onStaleTeacherRequestDiscarded(); return; }

        boolean anchorVisible=!state.designAnchor.isEmpty() && after.containsText(state.designAnchor);
        boolean homeVisible=after.looksLikeCanvaHome();
        boolean matchesLastSafe=!state.lastSafeSnapshotHash.isEmpty()
                && state.lastSafeSnapshotHash.equals(after.stableFingerprint());
        boolean visualEditorContinuityVerified=action.visualGrounded
                && DesignContinuityPolicy.visualEditorContinuityFromDistance(
                        visualDistance,preActionBoundDesignVerified,leaseOwnedVisualEvidence);
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
        if(persistenceHardHold.get()) return;
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
        if(persistenceHardHold.get()) return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        String pkg=root!=null&&root.getPackageName()!=null?root.getPackageName().toString():"";
        if(!AgentConstants.CANVA_PACKAGE.equals(pkg)){
            pauseForHuman("Ekran görüntüsü yalnız Canva açıkken alınabilir.");
            cycleBusy.set(false);
            return;
        }

        UiTreeSnapshot snap=UiTreeSnapshot.capture(root);
        TaskState state=repo.load();
        final String expectedPackage=pkg;
        final String expectedFingerprint=snap.stableFingerprint();
        final String expectedDesignAnchor=state.designAnchor;
        final String teacherSessionId=repo.currentTeacherSessionId();
        final String requestId=UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final TeacherRequestAuthority visualAuthority=TeacherRequestAuthority.beginVisual(requestId,expectedFingerprint);
        if(!visualAuthority.isValid() || !visualAuthority.stillOwnsTransport()){
            pauseForHuman("Görüntülü öğretmen için güvenli request authority oluşturulamadı; ekran görüntüsü gönderilmedi.");
            cycleBusy.set(false);
            return;
        }
        final String marker=visualAuthority.marker;
        final String visualExecutionToken=visualAuthority.executionLeaseToken;
        captureScreenshotForDiagnostics(file -> {
            if(persistenceHardHold.get()) return;
            if(!isTeacherSessionCurrent(teacherSessionId) || !visualAuthority.stillOwnsTransport()){
                onStaleTeacherRequestDiscarded();
                return;
            }
            if(file==null){
                pauseForHuman("Canva ekran görüntüsü alınamadı; tahmin ederek devam edilmedi.");
                cycleBusy.set(false);
                return;
            }

            AccessibilityNodeInfo liveRoot=getRootInActiveWindow();
            String livePackage=liveRoot!=null&&liveRoot.getPackageName()!=null
                    ?liveRoot.getPackageName().toString():"";
            UiTreeSnapshot liveSnap=liveRoot==null?null:UiTreeSnapshot.capture(liveRoot);
            TaskState liveState=repo.load();
            boolean liveAnchorVisible=liveSnap!=null && !liveState.designAnchor.isEmpty()
                    && liveSnap.containsText(liveState.designAnchor);
            boolean visualContextCurrent=liveSnap!=null && visualAuthority.snapshotFingerprint.equals(expectedFingerprint)
                    && VisualRequestContextGuard.matches(
                    expectedPackage,
                    livePackage,
                    visualAuthority.snapshotFingerprint,
                    liveSnap.stableFingerprint(),
                    expectedDesignAnchor,
                    liveState.designAnchor,
                    liveAnchorVisible,
                    liveSnap.looksLikeCanvaHome());
            if(!visualContextCurrent){
                file.delete();
                cycleBusy.set(false);
                runCanvaCycle("Ekran görüntüsü alınırken Canva UI/tasarım bağlamı değişti. Eski screenshot ve UI-tree öğretmene gönderilmedi; canlı ekranı baştan değerlendir.");
                return;
            }
            if(liveSnap.containsSensitiveInput()){
                file.delete();
                pauseForHuman("Ekran görüntüsü sırasında şifre / doğrulama alanı belirdi. Görsel öğretmene aktarılmadı; gerekli işlemi sen tamamla.");
                cycleBusy.set(false);
                return;
            }

            String visualHash=VisualFingerprint.fromFile(file);
            if(!visualEvidence.bindIfExecutionCurrent(visualExecutionToken,visualHash)){
                file.delete();
                onStaleTeacherRequestDiscarded();
                return;
            }
            String prompt=TeacherProtocol.buildVisualRequest(state,snap,visualAuthority,screenshotReason);
            teacher.askWithScreenshot(prompt,ScreenshotProvider.uriFor(file),visualAuthority,new TeacherBridge.ReplyCallback(){
                @Override public void onReply(String reply){
                    if(persistenceHardHold.get()) return;
                    if(!isTeacherSessionCurrent(teacherSessionId) || !visualAuthority.stillOwnsTransport()){
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
                            () -> waitForCanvaAndHandle(visualAction,visualAuthority.snapshotFingerprint,teacherSessionId,0),450);
                }

                @Override public void onFailure(String reason){
                    if(persistenceHardHold.get()) return;
                    if(!isTeacherSessionCurrent(teacherSessionId) || !visualAuthority.stillOwnsTransport()){
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
        if(persistenceHardHold.get()){
            enterPersistenceHardHold("Kalıcı durum belirsiz. Yeni görev bu servis ömründe başlatılamaz; erişilebilirlik servisini yeniden başlat.");
            return;
        }
        resumeGeneration.invalidate();
        TeacherExecutionLease.invalidateGlobal();
        visualEvidence.clear();
        cycleBusy.set(false);
        consecutiveNoVisualChange=0;
        consecutiveExecutionFailures=0;
        AccessibilityNodeInfo root=getRootInActiveWindow(); String fp="";
        if(root!=null && AgentConstants.CANVA_PACKAGE.equals(String.valueOf(root.getPackageName()))) fp=UiTreeSnapshot.capture(root).stableFingerprint();
        final String startFingerprint=fp;
        boolean started=ResumeUiTransitionGuard.runSafely(
                () -> repo.start(goal,allowNewDesign,startFingerprint));
        if(!started){
            enterPersistenceHardHold("Görev başlangıcı kalıcılaştırılamadı. Eski/yarım görev yetkisi kullanılmayacak; erişilebilirlik servisini yeniden başlat.");
            return;
        }
        overlay.hide();
        Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
        if(canva!=null){canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);startActivity(canva);}
    }

    public void stopTask(){
        final boolean wasHardHold=persistenceHardHold.get();
        resumeGeneration.invalidate();
        TeacherExecutionLease.invalidateGlobal();
        visualEvidence.clear();
        cycleBusy.set(false);
        consecutiveNoVisualChange=0;
        consecutiveExecutionFailures=0;
        boolean stopped=ResumeUiTransitionGuard.runSafely(repo::stop);
        if(!stopped){
            enterPersistenceHardHold("STOP durumu kalıcılaştırılamadı. Ajan bu servis ömründe hiçbir eylem yapmayacak.");
            return;
        }
        if(wasHardHold){
            enterPersistenceHardHold("STOP kalıcılaştırıldı ancak önceki persistence hatası nedeniyle bu servis ömründe ajan yeniden başlatılamaz. Erişilebilirlik servisini yeniden başlat.");
            return;
        }
        overlay.hide();
    }

    private void pauseForHuman(String reason){
        resumeGeneration.invalidate();
        TeacherExecutionLease.invalidateGlobal();
        visualEvidence.clear();
        boolean paused=ResumeUiTransitionGuard.runSafely(() -> repo.pauseForHuman(reason));
        if(!paused){
            enterPersistenceHardHold("Kullanıcıya bırakma durumu kalıcılaştırılamadı. Ajan işlem yapmayacak; erişilebilirlik servisini yeniden başlat.");
            return;
        }
        showHumanOverlay(reason);
    }

    private void enterPersistenceHardHold(String reason){
        persistenceHardHold.set(true);
        resumeGeneration.invalidate();
        TeacherExecutionLease.invalidateGlobal();
        visualEvidence.clear();
        cycleBusy.set(false);
        consecutiveNoVisualChange=0;
        consecutiveExecutionFailures=0;
        overlay.showHardHold(reason);
    }

    private void showHumanOverlay(String reason){
        if(persistenceHardHold.get()){
            overlay.showHardHold("Kalıcı durum belirsiz; servis yeniden başlatılmadan DEVAM ET kullanılamaz.");
            return;
        }
        overlay.show(reason,()->{
            if(persistenceHardHold.get()) throw new IllegalStateException("Persistence hard hold active");
            TeacherExecutionLease.invalidateGlobal();
            visualEvidence.clear();
            repo.resume();
            TaskState resumed=repo.load();
            if(resumed.mode!=TaskState.Mode.RUNNING){
                resumeGeneration.invalidate();
                return;
            }
            final long generation=resumeGeneration.begin();
            final String resumeSessionId=repo.currentTeacherSessionId();
            final String resumeDesignAnchor=resumed.designAnchor;
            cycleBusy.set(false);
            consecutiveNoVisualChange=0;
            consecutiveExecutionFailures=0;
            resumeOnCanva(generation,resumeSessionId,resumeDesignAnchor,0);
        });
    }

    public void onStaleTeacherRequestDiscarded(){
    }

    private boolean isTeacherSessionCurrent(String expectedSessionId){
        if(repo==null || persistenceHardHold.get()) return false;
        TaskState current=repo.load();
        return TeacherSessionPolicy.isCurrent(
                expectedSessionId,
                repo.currentTeacherSessionId(),
                current.mode
        );
    }

    private boolean isActionChainCurrent(AgentAction action, String expectedSessionId){
        return action!=null
                && !persistenceHardHold.get()
                && isTeacherSessionCurrent(expectedSessionId)
                && TeacherExecutionLease.isGlobalCurrent(action.executionLeaseToken);
    }

    private void runCanvaCycleIfActionCurrent(AgentAction action, String teacherSessionId, String note){
        if(persistenceHardHold.get()) return;
        if(!isActionChainCurrent(action,teacherSessionId)){
            onStaleTeacherRequestDiscarded();
            return;
        }
        runCanvaCycle(note);
    }

    private boolean isResumeContextCurrent(long generation, String expectedSessionId, String expectedDesignAnchor){
        if(persistenceHardHold.get() || !resumeGeneration.isCurrent(generation) || repo==null) return false;
        TaskState current=repo.load();
        return ResumeContextPolicy.isCurrent(
                current.mode,current.designAnchor,repo.currentTeacherSessionId(),
                expectedDesignAnchor,expectedSessionId);
    }

    private void resumeOnCanva(long generation, String expectedSessionId, String expectedDesignAnchor, int attempt){
        if(persistenceHardHold.get()) return;
        if(!isResumeContextCurrent(generation,expectedSessionId,expectedDesignAnchor)) return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        String pkg=root!=null&&root.getPackageName()!=null?root.getPackageName().toString():"";
        if(AgentConstants.CANVA_PACKAGE.equals(pkg)){
            if(!isResumeContextCurrent(generation,expectedSessionId,expectedDesignAnchor)) return;
            resumeGeneration.consumeIfCurrent(generation,()->{
                if(persistenceHardHold.get()) return;
                TaskState current=repo.load();
                if(!ResumeContextPolicy.isCurrent(current.mode,current.designAnchor,repo.currentTeacherSessionId(),
                        expectedDesignAnchor,expectedSessionId)) return;
                cycleBusy.set(false);
                runCanvaCycle("Kullanıcı müdahalesi tamamlandı. Önce mevcut durumu yeniden doğrula ve kaldığın görevden devam et.");
            });
            return;
        }

        if(attempt>=12){
            if(!isResumeContextCurrent(generation,expectedSessionId,expectedDesignAnchor)) return;
            resumeGeneration.consumeIfCurrent(generation,()->{
                if(persistenceHardHold.get()) return;
                TaskState current=repo.load();
                if(!ResumeContextPolicy.isCurrent(current.mode,current.designAnchor,repo.currentTeacherSessionId(),
                        expectedDesignAnchor,expectedSessionId)) return;
                pauseForHuman("Canva'ya güvenli biçimde dönülemedi. Canva'yı açıp DEVAM ET'e tekrar bas.");
            });
            return;
        }

        Intent canva=getPackageManager().getLaunchIntentForPackage(AgentConstants.CANVA_PACKAGE);
        if(canva!=null){
            canva.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            if(!resumeGeneration.runIfCurrent(generation,()->startActivity(canva))) return;
        }
        if(!isResumeContextCurrent(generation,expectedSessionId,expectedDesignAnchor)) return;
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> resumeOnCanva(generation,expectedSessionId,expectedDesignAnchor,attempt+1),300);
    }

    public void captureScreenshotForDiagnostics(ScreenshotCallback cb){
        if(persistenceHardHold.get()){ cb.onDone(null); return; }
        takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult result){
                if(persistenceHardHold.get()){ result.getHardwareBuffer().close(); cb.onDone(null); return; }
                HardwareBuffer hb=result.getHardwareBuffer();
                Bitmap bmp=Bitmap.wrapHardwareBuffer(hb,result.getColorSpace());
                if(bmp==null){hb.close();cb.onDone(null);return;}
                Bitmap copy=bmp.copy(Bitmap.Config.ARGB_8888,false);
                hb.close();
                if(copy==null){cb.onDone(null);return;}
                ScreenshotProvider.cleanupExpiredEvidence(getCacheDir(), System.currentTimeMillis());
                File f=new File(getCacheDir(),ScreenshotFilePolicy.newCaptureFileName());
                try(FileOutputStream os=new FileOutputStream(f,false)){
                    boolean encoded=copy.compress(Bitmap.CompressFormat.PNG,100,os);
                    os.getFD().sync();
                    if(!encoded || !ScreenshotFilePolicy.isCaptureFileName(f.getName()) || !f.isFile() || f.length()<=0L){
                        f.delete();
                        cb.onDone(null);
                    }else{
                        cb.onDone(f);
                    }
                }
                catch(Exception e){f.delete();cb.onDone(null);}
                finally{copy.recycle();}
            }
            @Override public void onFailure(int errorCode){cb.onDone(null);}
        });
    }

    public interface ScreenshotCallback{void onDone(File file);}
    @Override public void onInterrupt() {}
    @Override public void onDestroy(){
        persistenceHardHold.set(true);
        resumeGeneration.invalidate();
        TeacherExecutionLease.invalidateGlobal();
        visualEvidence.clear();
        if(overlay!=null) overlay.hide();
        INSTANCE=null;
        super.onDestroy();
    }
}