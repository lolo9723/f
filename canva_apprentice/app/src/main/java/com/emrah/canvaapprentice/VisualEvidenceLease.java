package com.emrah.canvaapprentice;

import android.view.accessibility.AccessibilityNodeInfo;

public final class VisualEvidenceLease {
    private String ownerExecutionToken="";
    private String visualHash="";
    private String ownerDesignAnchor="";
    private boolean ownerDesignContextCaptured=false;
    private String lastConsumedExecutionToken="";

    private static volatile RuntimeContext runtimeExpectedContext=null;
    private static volatile String runtimeExpectedOwnerExecutionToken="";
    private static volatile boolean runtimeExpectedForPostActionMeasurement=false;

    private static final class RuntimeContext {
        final String packageName;
        final String fingerprint;
        final String designAnchor;
        RuntimeContext(String packageName,String fingerprint,String designAnchor){
            this.packageName=packageName;
            this.fingerprint=fingerprint;
            this.designAnchor=designAnchor;
        }
    }

    synchronized void bind(String executionToken,String hash){
        if(executionToken==null||executionToken.isEmpty()||hash==null||hash.isEmpty()) return;
        ownerExecutionToken=executionToken;
        visualHash=hash;
        ownerDesignAnchor="";
        ownerDesignContextCaptured=false;
        lastConsumedExecutionToken="";
    }

    public synchronized boolean bindIfExecutionCurrent(String executionToken,String hash){
        if(hash==null||hash.isEmpty()) return false;
        return TeacherExecutionLease.withGlobalCurrent(executionToken,false,()->{
            RuntimeContext current=currentRuntimeContext();
            boolean serviceActive=AgentAccessibilityService.INSTANCE!=null;
            if(!mayBindRuntimeEvidence(serviceActive,current!=null)) return false;
            String currentAnchor=current==null?null:current.designAnchor;
            boolean captured=currentAnchor!=null;
            if(isOwnedBy(executionToken)){
                if(!visualHash.equals(hash)) return false;
                if(ownerDesignContextCaptured!=captured) return false;
                if(captured&&!ownerDesignAnchor.equals(currentAnchor)) return false;
                RuntimeContext expected=runtimeExpectedContext;
                if(expected!=null&&!executionToken.equals(runtimeExpectedOwnerExecutionToken)) return false;
                if(runtimeExpectedForPostActionMeasurement) return false;
                return expected==null||(current!=null&&executionContextMatches(
                        expected.packageName,current.packageName,
                        expected.fingerprint,current.fingerprint,
                        expected.designAnchor,current.designAnchor));
            }
            runtimeExpectedContext=current;
            runtimeExpectedOwnerExecutionToken=current==null?"":executionToken;
            runtimeExpectedForPostActionMeasurement=false;
            lastConsumedExecutionToken="";
            ownerExecutionToken=executionToken;
            visualHash=hash;
            ownerDesignContextCaptured=captured;
            ownerDesignAnchor=captured?currentAnchor:"";
            return true;
        });
    }

    static boolean mayBindRuntimeEvidence(boolean serviceActive,boolean contextPresent){
        return !serviceActive||contextPresent;
    }

    static boolean visualRuntimeEvidenceMayExecute(boolean serviceActive,boolean evidenceContextPresent,boolean evidenceContextCurrent){
        return !serviceActive||(evidenceContextPresent&&evidenceContextCurrent);
    }

    static boolean runtimeEvidenceMayBeRead(boolean serviceActive,boolean evidenceContextPresent,boolean evidenceContextCurrent){
        return !serviceActive||(evidenceContextPresent&&evidenceContextCurrent);
    }

    static boolean mayArmPostActionMeasurement(boolean serviceActive,boolean evidenceContextPresent,
            boolean evidenceOwnerMatches,boolean currentContextPresent,boolean canvaPackageMatches,
            boolean designIdentityMatches){
        if(!serviceActive) return true;
        return evidenceContextPresent&&evidenceOwnerMatches&&currentContextPresent
                &&canvaPackageMatches&&designIdentityMatches;
    }

    static boolean hasRuntimeExpectedContext(){
        return runtimeExpectedContext!=null&&!runtimeExpectedOwnerExecutionToken.isEmpty();
    }

    static boolean hasPendingPostActionMeasurement(){
        return runtimeExpectedForPostActionMeasurement&&hasRuntimeExpectedContext();
    }

    static boolean mayClearRuntimeExpectedContext(String clearingOwner,String runtimeOwner){
        return clearingOwner!=null&&!clearingOwner.isEmpty()&&runtimeOwner!=null&&clearingOwner.equals(runtimeOwner);
    }

    synchronized String readIfOwnedBy(String executionToken){
        if(!isOwnedBy(executionToken)||runtimeExpectedForPostActionMeasurement) return "";
        boolean serviceActive=AgentAccessibilityService.INSTANCE!=null;
        boolean present=hasRuntimeExpectedContext();
        boolean current=present&&isRuntimeDesignContextCurrent();
        if(!runtimeEvidenceMayBeRead(serviceActive,present,current)) return "";
        if(ownerDesignContextCaptured&&!ownerDesignAnchor.equals(currentRuntimeDesignAnchor())) return "";
        if(runtimeExpectedContext!=null){
            if(!executionToken.equals(runtimeExpectedOwnerExecutionToken)) return "";
            if(!isRuntimeDesignContextCurrent()) return "";
        }
        return visualHash;
    }

    public synchronized String readIfExecutionCurrent(String executionToken){
        return TeacherExecutionLease.withGlobalCurrent(executionToken,"",()->readIfOwnedBy(executionToken));
    }

    public synchronized String consumeIfExecutionCurrent(String executionToken){
        return TeacherExecutionLease.withGlobalCurrent(executionToken,"",()->{
            if(!isOwnedBy(executionToken)) return "";
            boolean serviceActive=AgentAccessibilityService.INSTANCE!=null;
            if(!serviceActive){
                String consumed=visualHash;
                clear();
                return consumed;
            }
            RuntimeContext expected=runtimeExpectedContext;
            RuntimeContext current=currentRuntimeContext();
            boolean present=expected!=null&&!runtimeExpectedOwnerExecutionToken.isEmpty();
            boolean ownerMatches=executionToken.equals(runtimeExpectedOwnerExecutionToken);
            boolean currentPresent=current!=null;
            boolean packageMatches=expected!=null&&current!=null
                    &&AgentConstants.CANVA_PACKAGE.equals(expected.packageName)
                    &&expected.packageName.equals(current.packageName);
            boolean designMatches=expected!=null&&current!=null&&!expected.designAnchor.isEmpty()
                    &&designIdentityMatches(expected.designAnchor,current.designAnchor)
                    &&(!ownerDesignContextCaptured||designIdentityMatches(ownerDesignAnchor,current.designAnchor));
            if(!mayArmPostActionMeasurement(true,present,ownerMatches,currentPresent,packageMatches,designMatches)) return "";

            String consumed=visualHash;
            lastConsumedExecutionToken=executionToken;
            ownerExecutionToken="";
            visualHash="";
            ownerDesignAnchor="";
            ownerDesignContextCaptured=false;
            runtimeExpectedContext=current;
            runtimeExpectedOwnerExecutionToken=executionToken;
            runtimeExpectedForPostActionMeasurement=true;
            return consumed;
        });
    }

    synchronized boolean clearIfOwnedBy(String executionToken){
        if(!isOwnedBy(executionToken)) return false;
        clear();
        return true;
    }

    public synchronized boolean clearIfExecutionCurrent(String executionToken){
        return TeacherExecutionLease.withGlobalCurrent(executionToken,false,()->{
            if(isOwnedBy(executionToken)) return clearIfOwnedBy(executionToken);
            if(executionToken!=null&&executionToken.equals(lastConsumedExecutionToken)
                    &&executionToken.equals(runtimeExpectedOwnerExecutionToken)){
                clear();
                return true;
            }
            return false;
        });
    }

    synchronized boolean isOwnedBy(String executionToken){
        return executionToken!=null&&!executionToken.isEmpty()&&executionToken.equals(ownerExecutionToken)&&!visualHash.isEmpty();
    }

    static boolean isRuntimeDesignContextCurrent(){
        RuntimeContext expected=runtimeExpectedContext;
        String owner=runtimeExpectedOwnerExecutionToken;
        if(expected==null||owner.isEmpty()) return true;
        if(!TeacherExecutionLease.isGlobalCurrent(owner)) return false;
        RuntimeContext current=currentRuntimeContext();
        return current!=null&&executionContextMatches(
                expected.packageName,current.packageName,
                expected.fingerprint,current.fingerprint,
                expected.designAnchor,current.designAnchor);
    }

    static synchronized boolean consumePostActionMeasurementContextIfCurrent(){
        if(!runtimeExpectedForPostActionMeasurement) return false;
        String owner=runtimeExpectedOwnerExecutionToken;
        boolean current=owner!=null&&!owner.isEmpty()&&TeacherExecutionLease.isGlobalCurrent(owner)
                &&isRuntimeDesignContextCurrent();
        runtimeExpectedContext=null;
        runtimeExpectedOwnerExecutionToken="";
        runtimeExpectedForPostActionMeasurement=false;
        return current;
    }

    static boolean designIdentityMatches(String expected,String current){
        return expected!=null&&current!=null&&expected.trim().equals(current.trim());
    }

    static boolean executionContextMatches(String expectedPackage,String currentPackage,String expectedFingerprint,
            String currentFingerprint,String expectedDesignAnchor,String currentDesignAnchor){
        if(expectedPackage==null||currentPackage==null||expectedFingerprint==null||currentFingerprint==null
                ||expectedDesignAnchor==null||currentDesignAnchor==null) return false;
        return AgentConstants.CANVA_PACKAGE.equals(expectedPackage)&&expectedPackage.equals(currentPackage)
                &&!expectedFingerprint.isEmpty()&&expectedFingerprint.equals(currentFingerprint)
                &&designIdentityMatches(expectedDesignAnchor,currentDesignAnchor);
    }

    private static RuntimeContext currentRuntimeContext(){
        AgentAccessibilityService service=AgentAccessibilityService.INSTANCE;
        if(service==null) return null;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        String packageName=root!=null&&root.getPackageName()!=null?root.getPackageName().toString():"";
        if(!AgentConstants.CANVA_PACKAGE.equals(packageName)||root==null) return null;
        UiTreeSnapshot snapshot=UiTreeSnapshot.capture(root);
        TaskState state=new TaskStateRepository(service).load();
        String designAnchor=state.designAnchor==null?null:state.designAnchor.trim();
        return new RuntimeContext(packageName,snapshot.stableFingerprint(),designAnchor);
    }

    private static String currentRuntimeDesignAnchor(){
        RuntimeContext current=currentRuntimeContext();
        return current==null?null:current.designAnchor;
    }

    public synchronized void clear(){
        String primaryOwner=ownerExecutionToken;
        String consumedOwner=lastConsumedExecutionToken;
        ownerExecutionToken="";
        visualHash="";
        ownerDesignAnchor="";
        ownerDesignContextCaptured=false;
        lastConsumedExecutionToken="";
        if(mayClearRuntimeExpectedContext(primaryOwner,runtimeExpectedOwnerExecutionToken)
                ||mayClearRuntimeExpectedContext(consumedOwner,runtimeExpectedOwnerExecutionToken)){
            runtimeExpectedContext=null;
            runtimeExpectedOwnerExecutionToken="";
            runtimeExpectedForPostActionMeasurement=false;
        }
    }

    synchronized String ownerTokenForTest(){ return ownerExecutionToken; }
}