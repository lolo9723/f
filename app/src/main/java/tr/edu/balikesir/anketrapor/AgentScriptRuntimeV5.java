package tr.edu.balikesir.anketrapor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Genel görev runtime: AGENT/1, /2 ve /3 çalıştırır.
 * UI erişimi bu sınıfta; veri/ifade mantığı AgentVm'de tutulur.
 */
final class AgentScriptRuntimeV5 {
    static final String PREF="yerel_agent_state";
    static final String SCRIPT_RUNNING="script_running_v5";
    static final String SCRIPT_INDEX="script_index_v5";
    static final String SCRIPT_STEPS="script_steps_v5";
    static final String SCRIPT_SAVED="agent_saved_script_v5";
    static final String LAST_RESULT="script_last_result_v5";
    private static final String VM_STATE="agent_vm_state_v5";
    private static final String LOOP_STATE="agent_loop_state_v5";
    private static final String OWN_APP="tr.edu.balikesir.yerelajan";

    private final AccessibilityService service;
    private final SharedPreferences state;
    private final SecureStore secure;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final AgentVm vm=new AgentVm();
    private int retryIndex=-1; private long retryStarted; private boolean busy; private boolean webRunning;

    AgentScriptRuntimeV5(AccessibilityService s){service=s;state=s.getSharedPreferences(PREF,AccessibilityService.MODE_PRIVATE);secure=new SecureStore(s);restoreVm();}
    boolean isRunning(){return state.getBoolean(SCRIPT_RUNNING,false);}

    void onEvent(AccessibilityEvent e){
        if(!isRunning()||webRunning)return; String pkg=safe(e==null?null:e.getPackageName());
        if(SafetyPolicy.isBlockedPackage(service,pkg)){stop("Hassas uygulama açıldığı için görev durduruldu.",false);return;}
        handler.removeCallbacks(pump);handler.postDelayed(pump,110);
    }

    boolean maybeStartFromOwnApp(AccessibilityEvent e){
        if(e==null||e.getEventType()!=AccessibilityEvent.TYPE_VIEW_CLICKED||!OWN_APP.equals(safe(e.getPackageName())))return false;
        String label=String.valueOf(e.getText());AccessibilityNodeInfo src=e.getSource();if(src!=null){label+=" "+safe(src.getText());src.recycle();}
        if(!label.contains("Hazırla")&&!label.contains("Çalıştır"))return false;handler.postDelayed(this::startIfAgentDialog,220);return true;
    }

    void interrupt(){if(isRunning())stop("Erişilebilirlik hizmeti kesildi.",false);}
    void destroy(){handler.removeCallbacksAndMessages(null);persistVm();}

    private void startIfAgentDialog(){
        if(!inAgentDialog())return;String latest=secure.get("last_text","");String saved=secure.get(SCRIPT_SAVED,"");String script=looksLikeAnyScript(latest)?latest:saved;
        if(script==null||script.trim().isEmpty()){toast("Önce Agent Script seç veya yapıştır.");return;}if(looksLikeAnyScript(latest))secure.put(SCRIPT_SAVED,latest);
        try{
            JSONArray steps;boolean needsClipboard;String name;
            if(AgentScriptEngineV3.looksLikeV3(script)){AgentScriptEngineV3.Plan p=AgentScriptEngineV3.parse(script);steps=p.steps;needsClipboard=p.needsClipboard;name=p.name;}
            else if(AgentScriptEngineV2.looksLikeV2(script)){AgentScriptEngineV2.Plan p=AgentScriptEngineV2.parse(script);steps=p.steps;needsClipboard=p.needsRuntimeClipboard;name=p.name;}
            else{AgentScriptEngine.Plan p=AgentScriptEngine.parse(script);steps=upgradeLegacy(p.steps);needsClipboard=p.needsRuntimeClipboard;name=p.name;}
            if(needsClipboard){String clip=clipboard();if(clip.trim().isEmpty()||clip.trim().equals(script.trim())){toast("Görev kodu kaydedildi. Çalışma metnini panoya kopyalayıp tekrar Çalıştır'a bas.");return;}}
            secure.put(SCRIPT_STEPS,steps.toString());secure.put(VM_STATE,"{}");secure.put(LOOP_STATE,"{}");vm.restore(new JSONObject());
            state.edit().putBoolean(SCRIPT_RUNNING,true).putBoolean("running",false).putBoolean("learning",false).putInt(SCRIPT_INDEX,0).putString(LAST_RESULT,"started").apply();
            busy=false;webRunning=false;resetRetry();toast("Ajan başladı: "+name);handler.post(pump);
        }catch(Exception ex){toast("Görev kodu hatalı: "+msg(ex));}
    }

    private JSONArray upgradeLegacy(JSONArray source)throws Exception{
        JSONArray out=new JSONArray();for(int i=0;i<source.length();i++){JSONObject s=source.optJSONObject(i);if(s==null)continue;
            if("car_search".equals(s.optString("kind"))){JSONObject w=new JSONObject();w.put("kind","web_research");w.put("spec",AgentScriptEngineV2.carSpec(s.optString("brand","Chevrolet"),s.optInt("min_year",2021),s.optInt("max_km",100000),s.optInt("count",4),s.optString("filename","Arac_Ilani.xlsx")));out.put(w);}else out.put(new JSONObject(s.toString()));}
        return out;
    }

    private boolean inAgentDialog(){AccessibilityNodeInfo root=service.getRootInActiveWindow();if(root==null)return false;try{List<AccessibilityNodeInfo> l=root.findAccessibilityNodeInfosByText("Özel Agent Görevi Çalıştır");boolean ok=l!=null&&!l.isEmpty();recycle(l);return ok;}catch(Exception e){return false;}finally{root.recycle();}}

    private final Runnable pump=this::pumpNow;
    private void pumpNow(){
        if(!isRunning()||busy||webRunning)return;JSONArray a=steps();int index=state.getInt(SCRIPT_INDEX,0);if(index>=a.length()){stop("Görev tamamlandı.",true);return;}JSONObject s=a.optJSONObject(index);if(s==null){advance(index,60);return;}
        try{
            switch(s.optString("kind","")){
                case "open_app":openApp(s,index);break;case "open_url":openUrl(s,index);break;case "google_search":googleSearch(s,index);break;
                case "wait":advance(index,clamp(s.optInt("ms",800),50,60000));break;case "tap_any":tapAny(s,index);break;case "set_any":setAny(s,index);break;
                case "ui_read_text":uiReadText(s,index);break;case "ui_wait_text":uiWaitText(s,index);break;
                case "back":service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);advance(index,600);break;case "swipe":swipe(s,index);break;case "share_ajan_album":shareAjanFolder(index);break;
                case "web_research":launchWebResearch(s,index);break;
                case "vm_set":vmSet(s,index);break;case "vm_clipboard":vmClipboard(s,index);break;case "vm_list_append":vmListAppend(s,index);break;
                case "vm_filter":vmFilter(s,index);break;case "vm_sort":vmSort(s,index);break;case "vm_dedupe":vmDedupe(s,index);break;case "vm_join":vmJoin(s,index);break;
                case "vm_assert":vmAssert(s,index);break;case "vm_jump_if_false":vmJumpIfFalse(s,index);break;case "vm_jump":jumpTo(s.optInt("target",index+1),50);break;
                case "vm_foreach_begin":vmForeachBegin(s,index);break;case "vm_foreach_end":vmForeachEnd(s,index);break;case "vm_repeat_begin":vmRepeatBegin(s,index);break;case "vm_repeat_end":vmRepeatEnd(s,index);break;
                case "vm_xlsx":vmXlsx(s,index);break;case "vm_status":vmStatus(s,index);break;
                case "stop":stop(s.optString("message","Görev tamamlandı."),true);break;default:stop("Desteklenmeyen çalışma adımı: "+s.optString("kind"),false);break;
            }
        }catch(Exception e){stop("Görev hatası: "+msg(e),false);}
    }

    private void vmSet(JSONObject s,int index){vm.set(s.optString("name"),vm.eval(s.opt("expr")));persistVm();advance(index,40);}
    private void vmClipboard(JSONObject s,int index){String v=clipboard();if(v.isEmpty()){stop("Pano boş.",false);return;}vm.set(s.optString("name"),v);persistVm();advance(index,40);}
    private void vmListAppend(JSONObject s,int index){String n=s.optString("name");Object old=vm.get(n);JSONArray a=new JSONArray();if(old instanceof JSONArray){try{a=new JSONArray(old.toString());}catch(Exception ignored){a=new JSONArray();}}a.put(vm.eval(s.opt("expr")));vm.set(n,a);persistVm();advance(index,40);}
    private void vmFilter(JSONObject s,int index){Object raw=vm.get(s.optString("source"));if(!(raw instanceof JSONArray)){stop("dataset.filter kaynağı liste değil.",false);return;}JSONArray r=vm.filter((JSONArray)raw,s.opt("where"),s.optString("item","row"));vm.set(s.optString("target"),r);persistVm();advance(index,40);}
    private void vmSort(JSONObject s,int index){Object raw=vm.get(s.optString("source"));if(!(raw instanceof JSONArray)){stop("dataset.sort kaynağı liste değil.",false);return;}JSONArray r=vm.sort((JSONArray)raw,s.opt("key"),s.optString("item","row"),s.optBoolean("ascending",true));vm.set(s.optString("target"),r);persistVm();advance(index,40);}
    private void vmDedupe(JSONObject s,int index){Object raw=vm.get(s.optString("source"));if(!(raw instanceof JSONArray)){stop("dataset.dedupe kaynağı liste değil.",false);return;}JSONArray r=vm.dedupe((JSONArray)raw,s.opt("key"),s.optString("item","row"));vm.set(s.optString("target"),r);persistVm();advance(index,40);}
    private void vmJoin(JSONObject s,int index){Object l=vm.get(s.optString("left")),r=vm.get(s.optString("right"));if(!(l instanceof JSONArray)||!(r instanceof JSONArray)){stop("dataset.join kaynakları liste değil.",false);return;}JSONArray j=vm.join((JSONArray)l,(JSONArray)r,s.opt("left_key"),s.opt("right_key"),s.optString("left_var","left"),s.optString("right_var","right"),"left".equalsIgnoreCase(s.optString("type")));vm.set(s.optString("target"),j);persistVm();advance(index,40);}
    private void vmAssert(JSONObject s,int index){if(!AgentVm.truthy(vm.eval(s.opt("expr")))){stop(s.optString("message","Görev doğrulaması başarısız."),false);return;}advance(index,40);}
    private void vmJumpIfFalse(JSONObject s,int index){if(!AgentVm.truthy(vm.eval(s.opt("expr"))))jumpTo(s.optInt("target",index+1),30);else advance(index,30);}

    private void vmForeachBegin(JSONObject s,int index){
        JSONObject loops=loops();String key="f"+index;JSONObject e=loops.optJSONObject(key);
        if(e==null){Object lv=vm.eval(s.opt("list"));if(!(lv instanceof JSONArray)){stop("foreach listesi liste değil.",false);return;}JSONArray list=(JSONArray)lv;if(list.length()==0){jumpTo(s.optInt("end",index+1),30);return;}e=new JSONObject();try{e.put("pos",0);e.put("list",new JSONArray(list.toString()));e.put("item",s.optString("item","item"));e.put("index_var",s.optString("index_var","index"));loops.put(key,e);}catch(Exception ex){stop("foreach durumu yazılamadı.",false);return;}saveLoops(loops);}
        JSONArray list=e.optJSONArray("list");int pos=e.optInt("pos",0);if(list==null||pos>=list.length()){loops.remove(key);saveLoops(loops);jumpTo(s.optInt("end",index+1),30);return;}
        vm.set(e.optString("item","item"),list.opt(pos));vm.set(e.optString("index_var","index"),pos);persistVm();advance(index,30);
    }
    private void vmForeachEnd(JSONObject s,int index){int begin=s.optInt("begin",-1);JSONObject loops=loops();String key="f"+begin;JSONObject e=loops.optJSONObject(key);if(e==null){advance(index,30);return;}JSONArray list=e.optJSONArray("list");int pos=e.optInt("pos",0)+1;try{e.put("pos",pos);}catch(Exception ignored){}if(list!=null&&pos<list.length()){saveLoops(loops);vm.set(e.optString("item","item"),list.opt(pos));vm.set(e.optString("index_var","index"),pos);persistVm();jumpTo(begin+1,30);}else{loops.remove(key);saveLoops(loops);advance(index,30);}}
    private void vmRepeatBegin(JSONObject s,int index){JSONObject loops=loops();String key="r"+index;JSONObject e=loops.optJSONObject(key);if(e==null){int count=(int)Math.round(toNumber(vm.eval(s.opt("count"))));count=clamp(count,0,500);if(count<=0){jumpTo(s.optInt("end",index+1),30);return;}e=new JSONObject();try{e.put("pos",0);e.put("count",count);loops.put(key,e);}catch(Exception ignored){}saveLoops(loops);}advance(index,30);}
    private void vmRepeatEnd(JSONObject s,int index){int begin=s.optInt("begin",-1);JSONObject loops=loops();String key="r"+begin;JSONObject e=loops.optJSONObject(key);if(e==null){advance(index,30);return;}int p=e.optInt("pos",0)+1,c=e.optInt("count",0);try{e.put("pos",p);}catch(Exception ignored){}if(p<c){saveLoops(loops);jumpTo(begin+1,30);}else{loops.remove(key);saveLoops(loops);advance(index,30);}}

    private void vmXlsx(JSONObject s,int index)throws Exception{
        Object raw=vm.get(s.optString("source"));if(!(raw instanceof JSONArray)){stop("Excel kaynağı liste değil.",false);return;}JSONArray data=(JSONArray)raw,cols=s.optJSONArray("columns");if(cols==null||cols.length()==0){stop("Excel kolonları yok.",false);return;}
        String[] headers=new String[cols.length()];int hyperlink=-1;String hHeader=s.optString("hyperlink_header","");for(int c=0;c<cols.length();c++){JSONObject col=cols.optJSONObject(c);headers[c]=col==null?"Alan"+(c+1):col.optString("header","Alan"+(c+1));if(headers[c].equals(hHeader))hyperlink=c;}
        ArrayList<String[]> rows=new ArrayList<>();Object oldRow=vm.get("row");boolean hadOld=!AgentVm.isNull(oldRow);
        for(int i=0;i<data.length();i++){Object row=data.opt(i);vm.set("row",row);String[] out=new String[cols.length()];for(int c=0;c<cols.length();c++){JSONObject col=cols.optJSONObject(c);Object v=JSONObject.NULL;if(col!=null&&col.has("expr"))v=vm.eval(col.opt("expr"));else if(col!=null&&col.has("field")&&row instanceof JSONObject)v=((JSONObject)row).opt(col.optString("field"));else if(row instanceof JSONObject)v=((JSONObject)row).opt(headers[c]);out[c]=AgentVm.text(v);}rows.add(out);}
        if(hadOld)vm.set("row",oldRow);persistVm();String path=SimpleXlsxWriter.write(service,s.optString("filename","Yerel_Ajan_Sonuc.xlsx"),headers,rows,hyperlink);vm.set("last_output_file",path);persistVm();toast("Excel hazır: "+path);advance(index,100);
    }
    private void vmStatus(JSONObject s,int index){String m=s.optString("message","");if(s.has("expr")){String v=AgentVm.text(vm.eval(s.opt("expr")));m=m.isEmpty()?v:m+" "+v;}if(!m.isEmpty())toast(m);advance(index,30);}

    private void launchWebResearch(JSONObject step,int index){
        JSONObject spec=step.optJSONObject("spec");if(spec==null){stop("Web görev tanımı yok.",false);return;}webRunning=true;busy=true;
        ResultReceiver rr=new ResultReceiver(handler){@Override protected void onReceiveResult(int code,Bundle b){webRunning=false;busy=false;String message=b==null?"":b.getString("message","");
            if(b!=null&&step.has("output")){String raw=b.getString("rows_json","[]");try{vm.set(step.optString("output"),new JSONArray(raw));vm.set(step.optString("output")+"_file",b.getString("path",b.getString("filename","")));persistVm();}catch(Exception ignored){}}
            if(code==WebResearchActivity.RESULT_FULL)advance(index,200);else if(code==WebResearchActivity.RESULT_PARTIAL&&spec.optBoolean("allow_partial",false))advance(index,200);else stop(message.isEmpty()?"Web araştırması hedefi tamamlayamadı.":message,false);
        }};
        Intent i=new Intent(service,WebResearchActivity.class);i.putExtra(WebResearchActivity.EXTRA_SPEC,spec.toString());i.putExtra(WebResearchActivity.EXTRA_RECEIVER,rr);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);try{service.startActivity(i);}catch(Exception e){webRunning=false;busy=false;stop("Web araştırma ekranı açılamadı: "+msg(e),false);}
    }

    private void openApp(JSONObject s,int index){String pkg=s.optString("package","").trim();if(pkg.isEmpty()){stop("OPEN_APP paket adı boş.",false);return;}if(SafetyPolicy.isBlockedPackage(service,pkg)){stop("Hassas uygulama engellendi.",false);return;}Intent i=service.getPackageManager().getLaunchIntentForPackage(pkg);if(i==null){stop("Uygulama bulunamadı: "+pkg,false);return;}i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);service.startActivity(i);advance(index,1000);}
    private void openUrl(JSONObject s,int index){String url=s.optString("url","").trim();if(!SafetyPolicy.isSafeUrl(url)){stop("URL güvenlik filtresinden geçmedi.",false);return;}Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse(url));i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);i.setPackage("com.android.chrome");try{service.startActivity(i);}catch(Exception e){i.setPackage(null);service.startActivity(i);}advance(index,1300);}
    private void googleSearch(JSONObject s,int index)throws Exception{JSONObject x=new JSONObject();x.put("url","https://www.google.com/search?q="+URLEncoder.encode(s.optString("query",""),StandardCharsets.UTF_8.name()));openUrl(x,index);}

    private void tapAny(JSONObject s,int index){AccessibilityNodeInfo root=service.getRootInActiveWindow();if(root==null){retry(s,index);return;}AccessibilityNodeInfo n=null;try{n=findAny(root,selectorArray(s));if(n==null){retry(s,index);return;}if(SafetyPolicy.isProtectedFinal(safe(n.getText()))||SafetyPolicy.isProtectedFinal(safe(n.getContentDescription()))){stop("Kritik son düğmeye dokunmadım.",true);return;}AccessibilityNodeInfo c=clickableAncestor(n);boolean ok=c!=null&&c.performAction(AccessibilityNodeInfo.ACTION_CLICK);if(c!=null)c.recycle();if(ok){resetRetry();advance(index,550);}else retry(s,index);}finally{if(n!=null)n.recycle();root.recycle();}}
    private void setAny(JSONObject s,int index){AccessibilityNodeInfo root=service.getRootInActiveWindow();if(root==null){retry(s,index);return;}AccessibilityNodeInfo n=null;try{n=findEditable(root,selectorArray(s));if(n==null){retry(s,index);return;}String value;if(s.has("value_expr"))value=AgentVm.text(vm.eval(s.opt("value_expr")));else value="clipboard".equals(s.optString("value_source"))?clipboard():s.optString("value","");if(value.isEmpty()&&"clipboard".equals(s.optString("value_source"))){stop("Pano boş.",false);return;}Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);if(n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b)){resetRetry();advance(index,400);}else retry(s,index);}finally{if(n!=null)n.recycle();root.recycle();}}
    private void uiReadText(JSONObject s,int index){AccessibilityNodeInfo root=service.getRootInActiveWindow();if(root==null){retry(s,index);return;}AccessibilityNodeInfo n=null;try{n=findAny(root,selectorArray(s));if(n==null){retry(s,index);return;}String v=safe(n.getText());if(v.isEmpty())v=safe(n.getContentDescription());vm.set(s.optString("name"),v);persistVm();resetRetry();advance(index,40);}finally{if(n!=null)n.recycle();root.recycle();}}
    private void uiWaitText(JSONObject s,int index){AccessibilityNodeInfo root=service.getRootInActiveWindow();if(root==null){retry(s,index);return;}AccessibilityNodeInfo n=null;try{n=findAny(root,selectorArray(s));if(n==null){retry(s,index);return;}resetRetry();advance(index,40);}finally{if(n!=null)n.recycle();root.recycle();}}
    private void swipe(JSONObject s,int index){Rect r=screenRect();boolean down="down".equalsIgnoreCase(s.optString("direction","up"));float x=r.width()*.5f,sy=down?r.height()*.30f:r.height()*.78f,ey=down?r.height()*.78f:r.height()*.30f;Path p=new Path();p.moveTo(x,sy);p.lineTo(x,ey);busy=true;service.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,400)).build(),new AccessibilityService.GestureResultCallback(){@Override public void onCompleted(GestureDescription g){busy=false;advance(index,550);}@Override public void onCancelled(GestureDescription g){busy=false;retry(s,index);}},null);}
    private void shareAjanFolder(int index){String tree=service.getSharedPreferences(FolderGrantActivity.PREF,AccessibilityService.MODE_PRIVATE).getString(FolderGrantActivity.KEY_URI,"");if(tree.isEmpty()){if(FolderGrantActivity.isActive())return;busy=true;FolderGrantActivity.setCompletionCallback(()->{busy=false;handler.postDelayed(pump,200);});Intent g=new Intent(service,FolderGrantActivity.class);g.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);service.startActivity(g);toast("Bir kez Ajan klasörünü seç.");return;}try{ArrayList<Uri> imgs=AgentScriptRuntimeV4Files.listImages(service,Uri.parse(tree),20);if(imgs.isEmpty()){stop("Ajan klasöründe görsel yok.",false);return;}Intent share=new Intent(Intent.ACTION_SEND_MULTIPLE);share.setType("image/*");share.setPackage("com.instagram.android");share.putParcelableArrayListExtra(Intent.EXTRA_STREAM,imgs);share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);for(Uri u:imgs)service.grantUriPermission("com.instagram.android",u,Intent.FLAG_GRANT_READ_URI_PERMISSION);service.startActivity(share);advance(index,1600);}catch(Exception e){stop("Ajan klasörü okunamadı: "+msg(e),false);}}

    private JSONArray steps(){try{return new JSONArray(secure.get(SCRIPT_STEPS,"[]"));}catch(Exception e){return new JSONArray();}}
    private JSONObject loops(){try{return new JSONObject(secure.get(LOOP_STATE,"{}"));}catch(Exception e){return new JSONObject();}}
    private void saveLoops(JSONObject x){secure.put(LOOP_STATE,x==null?"{}":x.toString());}
    private void persistVm(){secure.put(VM_STATE,vm.snapshot().toString());}
    private void restoreVm(){try{vm.restore(new JSONObject(secure.get(VM_STATE,"{}")));}catch(Exception ignored){vm.restore(new JSONObject());}}
    private void advance(int index,long delay){state.edit().putInt(SCRIPT_INDEX,index+1).apply();resetRetry();handler.removeCallbacks(pump);handler.postDelayed(pump,delay);}
    private void jumpTo(int target,long delay){int max=steps().length();int t=Math.max(0,Math.min(max,target));state.edit().putInt(SCRIPT_INDEX,t).apply();resetRetry();handler.removeCallbacks(pump);handler.postDelayed(pump,delay);}
    private void retry(JSONObject s,int index){long now=SystemClock.uptimeMillis();if(retryIndex!=index){retryIndex=index;retryStarted=now;}if(now-retryStarted>clamp(s.optInt("timeout",s.optInt("timeout_ms",10000)),500,60000)){stop("Gerekli ekran öğesi bulunamadı. Adım: "+(index+1),false);return;}handler.postDelayed(pump,280);}
    private void resetRetry(){retryIndex=-1;retryStarted=0;}
    private void stop(String m,boolean ok){persistVm();state.edit().putBoolean(SCRIPT_RUNNING,false).putString(LAST_RESULT,ok?"success":"error").apply();busy=false;webRunning=false;resetRetry();handler.removeCallbacks(pump);toast(m);}

    private JSONArray selectorArray(JSONObject s){JSONArray a=s.optJSONArray("texts");if(a==null)a=s.optJSONArray("any");return a==null?new JSONArray():a;}
    private AccessibilityNodeInfo findAny(AccessibilityNodeInfo root,JSONArray a){if(a==null)return null;for(int i=0;i<a.length();i++){AccessibilityNodeInfo n=findText(root,a.optString(i));if(n!=null)return n;}return null;}
    private AccessibilityNodeInfo findText(AccessibilityNodeInfo root,String w){if(w==null||w.trim().isEmpty())return null;try{List<AccessibilityNodeInfo> l=root.findAccessibilityNodeInfosByText(w);String nw=SafetyPolicy.normalize(w);if(l!=null)for(AccessibilityNodeInfo n:l){String t=SafetyPolicy.normalize(safe(n.getText())),d=SafetyPolicy.normalize(safe(n.getContentDescription()));if(t.contains(nw)||d.contains(nw)){AccessibilityNodeInfo r=AccessibilityNodeInfo.obtain(n);recycle(l);return r;}}recycle(l);}catch(Exception ignored){}return null;}
    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root,JSONArray labels){AccessibilityNodeInfo h=findAny(root,labels);if(h!=null){if(h.isEditable()){AccessibilityNodeInfo r=AccessibilityNodeInfo.obtain(h);h.recycle();return r;}AccessibilityNodeInfo p=h.getParent();h.recycle();for(int d=0;p!=null&&d<4;d++){AccessibilityNodeInfo e=firstEditable(p);if(e!=null){p.recycle();return e;}AccessibilityNodeInfo q=p.getParent();p.recycle();p=q;}}return firstEditable(root);}
    private AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo root){ArrayList<AccessibilityNodeInfo> q=new ArrayList<>();q.add(AccessibilityNodeInfo.obtain(root));for(int i=0;i<q.size()&&i<1200;i++){AccessibilityNodeInfo n=q.get(i);if(n.isEditable()&&n.isVisibleToUser()){AccessibilityNodeInfo r=AccessibilityNodeInfo.obtain(n);recycle(q);return r;}for(int c=0;c<n.getChildCount();c++){AccessibilityNodeInfo ch=n.getChild(c);if(ch!=null)q.add(ch);}}recycle(q);return null;}
    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n){AccessibilityNodeInfo c=AccessibilityNodeInfo.obtain(n);for(int i=0;i<6&&c!=null;i++){if(c.isClickable())return c;AccessibilityNodeInfo p=c.getParent();c.recycle();c=p;}return null;}
    private Rect screenRect(){Rect r=new Rect();AccessibilityNodeInfo root=service.getRootInActiveWindow();if(root!=null){root.getBoundsInScreen(r);root.recycle();}if(r.width()<=0||r.height()<=0)r.set(0,0,service.getResources().getDisplayMetrics().widthPixels,service.getResources().getDisplayMetrics().heightPixels);return r;}
    private String clipboard(){try{ClipboardManager cm=(ClipboardManager)service.getSystemService(AccessibilityService.CLIPBOARD_SERVICE);ClipData d=cm==null?null:cm.getPrimaryClip();if(d==null||d.getItemCount()==0)return "";CharSequence x=d.getItemAt(0).coerceToText(service);return x==null?"":x.toString();}catch(Exception e){return "";}}
    private boolean looksLikeAnyScript(String s){if(s==null)return false;String x=s.trim();return AgentScriptEngineV3.looksLikeV3(x)||AgentScriptEngineV2.looksLikeV2(x)||AgentScriptEngine.looksLikeScript(x);}
    private double toNumber(Object v){if(v instanceof Number)return ((Number)v).doubleValue();try{return Double.parseDouble(AgentVm.text(v).replace(',','.').replaceAll("[^0-9+\\-.]",""));}catch(Exception e){return 0;}}
    private int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    private void toast(String s){Toast.makeText(service,s,Toast.LENGTH_LONG).show();}
    private String msg(Exception e){return e==null?"bilinmeyen hata":(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
    private static String safe(CharSequence s){return s==null?"":s.toString();}
    private static void recycle(List<AccessibilityNodeInfo> l){if(l!=null)for(AccessibilityNodeInfo n:l)if(n!=null)n.recycle();}
}
