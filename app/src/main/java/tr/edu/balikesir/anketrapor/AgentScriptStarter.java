package tr.edu.balikesir.anketrapor;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** 20. modül için Accessibility click/dialog tahminine ihtiyaç duymayan deterministik başlatıcı. */
final class AgentScriptStarter {
    private AgentScriptStarter() {}

    static final class Result {
        final boolean ok; final String message; final String name;
        Result(boolean ok,String message,String name){this.ok=ok;this.message=message;this.name=name;}
    }

    static Result start(AccessibilityService service){
        if(service==null)return new Result(false,"Erişilebilirlik servisi bağlı değil.","");
        SecureStore secure=new SecureStore(service);
        String latest=secure.get("last_text","");
        String saved=secure.get(AgentScriptRuntimeV5.SCRIPT_SAVED,"");
        String script=looksLikeScript(latest)?latest:saved;
        if(script==null||script.trim().isEmpty())return new Result(false,"Önce Agent Script seç veya yapıştır.","");
        if(looksLikeScript(latest))secure.put(AgentScriptRuntimeV5.SCRIPT_SAVED,latest);

        try{
            JSONArray steps; boolean needsClipboard; String name;
            if(AgentScriptEngineV3.looksLikeV3(script)){
                AgentScriptEngineV3.Plan p=AgentScriptEngineV3.parse(script);
                steps=p.steps;needsClipboard=p.needsClipboard;name=p.name;
            }else if(AgentScriptEngineV2.looksLikeV2(script)){
                AgentScriptEngineV2.Plan p=AgentScriptEngineV2.parse(script);
                steps=p.steps;needsClipboard=p.needsRuntimeClipboard;name=p.name;
            }else{
                AgentScriptEngine.Plan p=AgentScriptEngine.parse(script);
                steps=upgradeLegacy(p.steps);needsClipboard=p.needsRuntimeClipboard;name=p.name;
            }
            if(needsClipboard){
                String clip=clipboard(service);
                if(clip.trim().isEmpty()||clip.trim().equals(script.trim())){
                    return new Result(false,"Görev çalışma verisi için pano bekliyor. Görev kodundan farklı çalışma metnini panoya kopyala.",name);
                }
            }
            secure.put(AgentScriptRuntimeV5.SCRIPT_STEPS,steps.toString());
            secure.put("agent_vm_state_v5","{}");
            secure.put("agent_loop_state_v5","{}");
            SharedPreferences state=service.getSharedPreferences(AgentScriptRuntimeV5.PREF,AccessibilityService.MODE_PRIVATE);
            state.edit()
                    .putBoolean(AgentScriptRuntimeV5.SCRIPT_RUNNING,true)
                    .putBoolean("running",false)
                    .putBoolean("learning",false)
                    .putInt(AgentScriptRuntimeV5.SCRIPT_INDEX,0)
                    .putString(AgentScriptRuntimeV5.LAST_RESULT,"started")
                    .apply();
            return new Result(true,"Ajan başladı: "+name,name);
        }catch(Exception e){
            String m=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            return new Result(false,"Görev kodu hatalı: "+m,"");
        }
    }

    private static JSONArray upgradeLegacy(JSONArray source)throws Exception{
        JSONArray out=new JSONArray();
        for(int i=0;i<source.length();i++){
            JSONObject s=source.optJSONObject(i);if(s==null)continue;
            if("car_search".equals(s.optString("kind"))){
                JSONObject w=new JSONObject();w.put("kind","web_research");
                w.put("spec",AgentScriptEngineV2.carSpec(
                        s.optString("brand","Chevrolet"),s.optInt("min_year",2021),
                        s.optInt("max_km",100000),s.optInt("count",4),
                        s.optString("filename","Arac_Ilani.xlsx")));
                out.put(w);
            }else out.put(new JSONObject(s.toString()));
        }
        return out;
    }

    private static String clipboard(AccessibilityService service){
        try{
            ClipboardManager cm=(ClipboardManager)service.getSystemService(AccessibilityService.CLIPBOARD_SERVICE);
            ClipData d=cm==null?null:cm.getPrimaryClip();
            if(d==null||d.getItemCount()==0)return "";
            CharSequence x=d.getItemAt(0).coerceToText(service);
            return x==null?"":x.toString();
        }catch(Exception e){return "";}
    }

    private static boolean looksLikeScript(String s){
        if(s==null)return false;String x=s.trim();
        return x.startsWith("AGENT/")||x.startsWith("TASK:")||
                (x.startsWith("{")&&(x.contains("\"steps\"")||x.contains("\"version\"")));
    }

    static boolean selfTest(){
        return looksLikeScript("AGENT/3 {\"version\":3,\"steps\":[]}")
                &&looksLikeScript("TASK: demo")
                &&!looksLikeScript("normal metin");
    }
}
