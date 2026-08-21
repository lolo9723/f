package tr.edu.balikesir.anketrapor;

import org.json.JSONArray;
import org.json.JSONObject;

/** AGENT/3: runtime değişken, IF, foreach, dataset ve çıktı komutlarını derler. */
final class AgentScriptEngineV3 {
    private AgentScriptEngineV3() {}

    static final class Plan {
        final String name; final JSONArray steps; final boolean needsClipboard;
        Plan(String n, JSONArray s, boolean c){name=n;steps=s;needsClipboard=c;}
    }

    static boolean looksLikeV3(String raw){
        if(raw==null)return false; String s=raw.trim();
        return s.startsWith("AGENT/3") || (s.startsWith("{") && s.contains("\"version\":3"));
    }

    static Plan parse(String raw) throws Exception {
        if(raw==null||raw.trim().isEmpty())throw new IllegalArgumentException("Görev kodu boş.");
        String s=raw.trim(); if(s.startsWith("AGENT/3"))s=s.substring(7).trim();
        JSONObject root=new JSONObject(s); if(root.optInt("version",3)!=3)throw new IllegalArgumentException("AGENT/3 version bekleniyor.");
        JSONArray src=root.optJSONArray("steps"); if(src==null||src.length()==0)throw new IllegalArgumentException("steps boş.");
        CompileCtx c=new CompileCtx(); compile(src,c,0);
        if(c.out.length()>1200)throw new IllegalArgumentException("Derlenmiş görev 1200 adımdan büyük olamaz.");
        return new Plan(root.optString("name",root.optString("task","Genel görev")),c.out,c.clipboard);
    }

    private static void compile(JSONArray src, CompileCtx c, int depth) throws Exception {
        if(depth>8)throw new IllegalArgumentException("Kontrol akışı çok derin.");
        if(src.length()>300)throw new IllegalArgumentException("Bir blok en fazla 300 adım olabilir.");
        for(int i=0;i<src.length();i++){
            JSONObject x=src.optJSONObject(i); if(x==null)throw new IllegalArgumentException("Adım nesne olmalı: "+(i+1));
            String op=x.optString("op","").trim().toLowerCase(); if(op.isEmpty())throw new IllegalArgumentException("op eksik: "+(i+1));
            switch(op){
                case "if": compileIf(x,c,depth); break;
                case "foreach": compileForeach(x,c,depth); break;
                case "repeat": compileRepeat(x,c,depth); break;
                case "var.set": add(c,"vm_set","name",req(x,"name"),"expr",exprOrValue(x)); break;
                case "clipboard.read": c.clipboard=true; add(c,"vm_clipboard","name",req(x,"name")); break;
                case "list.append": add(c,"vm_list_append","name",req(x,"name"),"expr",x.opt("value")); break;
                case "dataset.filter": add(c,"vm_filter","source",req(x,"source"),"target",x.optString("target",req(x,"source")),"item",x.optString("item","row"),"where",requiredObj(x,"where")); break;
                case "dataset.sort": add(c,"vm_sort","source",req(x,"source"),"target",x.optString("target",req(x,"source")),"item",x.optString("item","row"),"key",requiredAny(x,"key"),"ascending",x.optBoolean("ascending",true)); break;
                case "dataset.dedupe": add(c,"vm_dedupe","source",req(x,"source"),"target",x.optString("target",req(x,"source")),"item",x.optString("item","row"),"key",requiredAny(x,"key")); break;
                case "dataset.join": {
                    JSONObject s=new JSONObject(); s.put("kind","vm_join"); s.put("left",req(x,"left")); s.put("right",req(x,"right")); s.put("target",req(x,"target"));
                    s.put("left_var",x.optString("left_var","left")); s.put("right_var",x.optString("right_var","right"));
                    s.put("left_key",requiredAny(x,"left_key")); s.put("right_key",requiredAny(x,"right_key")); s.put("type",x.optString("type","inner")); c.out.put(s); break;
                }
                case "assert": add(c,"vm_assert","expr",requiredAny(x,"expr"),"message",x.optString("message","Görev doğrulaması başarısız.")); break;
                case "app.open": add(c,"open_app","package",req(x,"package")); break;
                case "url.open": add(c,"open_url","url",req(x,"url")); break;
                case "ui.tap": addUi(c,"tap_any",x); break;
                case "ui.set_text": {
                    JSONObject s=new JSONObject();s.put("kind","set_any");s.put("texts",selectors(x));s.put("timeout",bounded(x.optInt("timeout_ms",10000),500,60000));
                    if(x.has("expr")){s.put("value_expr",x.opt("expr"));}
                    else if("clipboard".equalsIgnoreCase(x.optString("source"))){s.put("value_source","clipboard");c.clipboard=true;}
                    else{s.put("value_source","literal");s.put("value",x.optString("value",""));} c.out.put(s); break;
                }
                case "ui.read_text": add(c,"ui_read_text","name",req(x,"name"),"any",selectors(x),"timeout",bounded(x.optInt("timeout_ms",10000),500,60000)); break;
                case "ui.wait_text": add(c,"ui_wait_text","any",selectors(x),"timeout",bounded(x.optInt("timeout_ms",10000),500,60000)); break;
                case "wait": add(c,"wait","ms",bounded(x.optInt("ms",800),50,60000)); break;
                case "back": add(c,"back"); break;
                case "swipe": add(c,"swipe","direction",x.optString("direction","up")); break;
                case "instagram.share_ajan_folder": add(c,"share_ajan_album"); break;
                case "web.search_extract": {
                    JSONObject s=new JSONObject();s.put("kind","web_research");s.put("spec",AgentScriptEngineV2.validateWebSpecForV3(x));
                    if(x.has("output"))s.put("output",x.optString("output")); c.out.put(s);break;
                }
                case "output.xlsx": {
                    JSONObject s=new JSONObject();s.put("kind","vm_xlsx");s.put("source",req(x,"source"));s.put("filename",safeFile(x.optString("filename","Yerel_Ajan_Sonuc.xlsx")));
                    JSONArray cols=x.optJSONArray("columns");if(cols==null||cols.length()==0||cols.length()>40)throw new IllegalArgumentException("output.xlsx columns 1-40 olmalı.");s.put("columns",new JSONArray(cols.toString()));
                    s.put("hyperlink_header",x.optString("hyperlink_header",""));c.out.put(s);break;
                }
                case "status": add(c,"vm_status","message",x.optString("message",""),"expr",x.opt("expr")); break;
                case "stop": add(c,"stop","message",x.optString("message","Görev tamamlandı.")); break;
                default: throw new IllegalArgumentException("Desteklenmeyen AGENT/3 op: "+op);
            }
        }
    }

    private static void compileIf(JSONObject x, CompileCtx c, int depth) throws Exception {
        Object cond=requiredAny(x,"condition"); JSONArray thenA=x.optJSONArray("then"); if(thenA==null)thenA=new JSONArray(); JSONArray elseA=x.optJSONArray("else");
        JSONObject jf=new JSONObject();jf.put("kind","vm_jump_if_false");jf.put("expr",cond);jf.put("target",-1);int jfIndex=c.out.length();c.out.put(jf);
        compile(thenA,c,depth+1);
        if(elseA!=null&&elseA.length()>0){JSONObject j=new JSONObject();j.put("kind","vm_jump");j.put("target",-1);int jIndex=c.out.length();c.out.put(j);jf.put("target",c.out.length());compile(elseA,c,depth+1);j.put("target",c.out.length());}
        else jf.put("target",c.out.length());
    }

    private static void compileForeach(JSONObject x, CompileCtx c, int depth) throws Exception {
        JSONArray body=x.optJSONArray("steps");if(body==null)throw new IllegalArgumentException("foreach.steps eksik.");
        JSONObject b=new JSONObject();b.put("kind","vm_foreach_begin");b.put("list",requiredAny(x,"list"));b.put("item",x.optString("item","item"));b.put("index_var",x.optString("index","index"));b.put("end",-1);
        int bi=c.out.length();c.out.put(b);compile(body,c,depth+1);JSONObject e=new JSONObject();e.put("kind","vm_foreach_end");e.put("begin",bi);c.out.put(e);b.put("end",c.out.length());
    }

    private static void compileRepeat(JSONObject x, CompileCtx c, int depth) throws Exception {
        JSONArray body=x.optJSONArray("steps");if(body==null)throw new IllegalArgumentException("repeat.steps eksik.");
        Object count=x.has("count_expr")?x.opt("count_expr"):x.opt("count");if(count==null)count=1;
        JSONObject b=new JSONObject();b.put("kind","vm_repeat_begin");b.put("count",count);b.put("end",-1);int bi=c.out.length();c.out.put(b);compile(body,c,depth+1);JSONObject e=new JSONObject();e.put("kind","vm_repeat_end");e.put("begin",bi);c.out.put(e);b.put("end",c.out.length());
    }

    private static void addUi(CompileCtx c,String kind,JSONObject x)throws Exception{JSONObject s=new JSONObject();s.put("kind",kind);s.put("texts",selectors(x));s.put("timeout",bounded(x.optInt("timeout_ms",10000),500,60000));c.out.put(s);}
    private static JSONArray selectors(JSONObject x)throws Exception{JSONArray a=x.optJSONArray("any");if(a!=null&&a.length()>0)return new JSONArray(a.toString());String t=x.optString("text","").trim();if(t.isEmpty())throw new IllegalArgumentException("UI seçicisi eksik.");return new JSONArray().put(t);}
    private static Object exprOrValue(JSONObject x){return x.has("expr")?x.opt("expr"):x.opt("value");}
    private static Object requiredAny(JSONObject x,String k){if(!x.has(k))throw new IllegalArgumentException(k+" eksik.");return x.opt(k);}
    private static JSONObject requiredObj(JSONObject x,String k){JSONObject o=x.optJSONObject(k);if(o==null)throw new IllegalArgumentException(k+" nesne olmalı.");return o;}
    private static String req(JSONObject x,String k){String s=x.optString(k,"").trim();if(s.isEmpty())throw new IllegalArgumentException(k+" eksik.");return s;}
    private static int bounded(int v,int min,int max){return Math.max(min,Math.min(max,v));}
    private static String safeFile(String s){if(s==null||s.trim().isEmpty())s="Yerel_Ajan_Sonuc.xlsx";s=s.replaceAll("[\\\\/:*?\"<>|]","_").trim();return s.toLowerCase().endsWith(".xlsx")?s:s+".xlsx";}

    private static void add(CompileCtx c,String kind,Object...kv)throws Exception{JSONObject s=new JSONObject();s.put("kind",kind);for(int i=0;i+1<kv.length;i+=2)if(kv[i+1]!=null)s.put(String.valueOf(kv[i]),kv[i+1]);c.out.put(s);}
    private static final class CompileCtx{final JSONArray out=new JSONArray();boolean clipboard;}

    static boolean selfTest(){
        try{
            String s="AGENT/3 {\"version\":3,\"steps\":[{\"op\":\"var.set\",\"name\":\"x\",\"value\":2},{\"op\":\"if\",\"condition\":{\"op\":\"gt\",\"args\":[{\"var\":\"x\"},1]},\"then\":[{\"op\":\"var.set\",\"name\":\"ok\",\"value\":true}]},{\"op\":\"foreach\",\"list\":[1,2],\"item\":\"n\",\"steps\":[{\"op\":\"status\",\"expr\":{\"var\":\"n\"}}]}]}";
            Plan p=parse(s);return p.steps.length()>=7;
        }catch(Exception e){return false;}
    }
}
