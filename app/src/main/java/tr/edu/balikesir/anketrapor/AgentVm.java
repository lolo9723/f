package tr.edu.balikesir.anketrapor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AGENT genel görev dili için küçük, deterministik veri/ifade sanal makinesi.
 * Ağ, dosya veya UI erişimi YOKTUR. Yalnız JSON değerleri üzerinde çalışır.
 */
final class AgentVm {
    private final JSONObject vars = new JSONObject();

    JSONObject snapshot() {
        try { return new JSONObject(vars.toString()); }
        catch (Exception e) { return new JSONObject(); }
    }

    void restore(JSONObject o) {
        if (o == null) return;
        List<String> keys = new ArrayList<>();
        java.util.Iterator<String> it = vars.keys();
        while (it.hasNext()) keys.add(it.next());
        for (String k : keys) vars.remove(k);
        it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            try { vars.put(k, deepCopy(o.opt(k))); } catch (Exception ignored) {}
        }
    }

    Object get(String path) {
        if (path == null || path.trim().isEmpty()) return JSONObject.NULL;
        String[] parts = path.split("\\.");
        Object cur = vars;
        for (String part : parts) {
            if (cur instanceof JSONObject) {
                cur = ((JSONObject) cur).opt(part);
            } else if (cur instanceof JSONArray && isInt(part)) {
                cur = ((JSONArray) cur).opt(Integer.parseInt(part));
            } else return JSONObject.NULL;
            if (cur == null) return JSONObject.NULL;
        }
        return cur;
    }

    void set(String path, Object value) {
        if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("Değişken adı boş.");
        String[] parts = path.split("\\.");
        JSONObject cur = vars;
        for (int i = 0; i < parts.length - 1; i++) {
            String p = parts[i];
            JSONObject next = cur.optJSONObject(p);
            if (next == null) {
                next = new JSONObject();
                try { cur.put(p, next); } catch (Exception e) { throw new IllegalArgumentException("Değişken yolu yazılamadı."); }
            }
            cur = next;
        }
        try { cur.put(parts[parts.length - 1], value == null ? JSONObject.NULL : deepCopy(value)); }
        catch (Exception e) { throw new IllegalArgumentException("Değişken yazılamadı: " + path); }
    }

    Object eval(Object expr) {
        if (expr == null || expr == JSONObject.NULL) return JSONObject.NULL;
        if (expr instanceof String || expr instanceof Number || expr instanceof Boolean || expr instanceof JSONArray) return deepCopy(expr);
        if (!(expr instanceof JSONObject)) return String.valueOf(expr);
        JSONObject x = (JSONObject) expr;
        if (x.has("var")) return deepCopy(get(x.optString("var")));
        String op = x.optString("op", "literal").toLowerCase(Locale.ROOT);
        JSONArray args = x.optJSONArray("args");
        switch (op) {
            case "literal": return deepCopy(x.opt("value"));
            case "add": return numericFold(args, 0d, '+');
            case "mul": return numericFold(args, 1d, '*');
            case "sub": return num(arg(args,0)) - num(arg(args,1));
            case "div": {
                double d = num(arg(args,1));
                if (Math.abs(d) < 1e-12) throw new IllegalArgumentException("Sıfıra bölme.");
                return num(arg(args,0)) / d;
            }
            case "mod": {
                double d = num(arg(args,1)); if (Math.abs(d) < 1e-12) throw new IllegalArgumentException("Sıfıra mod.");
                return num(arg(args,0)) % d;
            }
            case "round": return Math.round(num(arg(args,0)));
            case "floor": return Math.floor(num(arg(args,0)));
            case "ceil": return Math.ceil(num(arg(args,0)));
            case "min": return numericMinMax(args, true);
            case "max": return numericMinMax(args, false);
            case "eq": return equalsLoose(eval(arg(args,0)), eval(arg(args,1)));
            case "ne": return !equalsLoose(eval(arg(args,0)), eval(arg(args,1)));
            case "gt": return compare(eval(arg(args,0)), eval(arg(args,1))) > 0;
            case "gte": return compare(eval(arg(args,0)), eval(arg(args,1))) >= 0;
            case "lt": return compare(eval(arg(args,0)), eval(arg(args,1))) < 0;
            case "lte": return compare(eval(arg(args,0)), eval(arg(args,1))) <= 0;
            case "and": {
                if (args == null) return false; for (int i=0;i<args.length();i++) if (!truthy(eval(args.opt(i)))) return false; return true;
            }
            case "or": {
                if (args == null) return false; for (int i=0;i<args.length();i++) if (truthy(eval(args.opt(i)))) return true; return false;
            }
            case "not": return !truthy(eval(arg(args,0)));
            case "concat": {
                StringBuilder b = new StringBuilder(); if (args != null) for (int i=0;i<args.length();i++) b.append(text(eval(args.opt(i)))); return b.toString();
            }
            case "lower": return text(eval(arg(args,0))).toLowerCase(new Locale("tr","TR"));
            case "upper": return text(eval(arg(args,0))).toUpperCase(new Locale("tr","TR"));
            case "trim": return text(eval(arg(args,0))).trim();
            case "contains": return norm(text(eval(arg(args,0)))).contains(norm(text(eval(arg(args,1)))));
            case "starts_with": return text(eval(arg(args,0))).startsWith(text(eval(arg(args,1))));
            case "ends_with": return text(eval(arg(args,0))).endsWith(text(eval(arg(args,1))));
            case "replace": return text(eval(arg(args,0))).replace(text(eval(arg(args,1))), text(eval(arg(args,2))));
            case "regex": {
                String input = text(eval(arg(args,0))), pat = text(eval(arg(args,1)));
                int group = args != null && args.length() > 2 ? (int) num(arg(args,2)) : 0;
                Matcher m = Pattern.compile(pat, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE).matcher(input);
                if (!m.find()) return JSONObject.NULL;
                if (group < 0 || group > m.groupCount()) group = 0;
                String g = m.group(group); return g == null ? JSONObject.NULL : g;
            }
            case "to_number": return num(eval(arg(args,0)));
            case "to_text": return text(eval(arg(args,0)));
            case "length": {
                Object v = eval(arg(args,0)); if (v instanceof JSONArray) return ((JSONArray)v).length(); if (v instanceof JSONObject) return ((JSONObject)v).length(); return text(v).length();
            }
            case "get": {
                Object base = eval(arg(args,0)); Object key = eval(arg(args,1));
                if (base instanceof JSONObject) return deepCopy(((JSONObject)base).opt(text(key)));
                if (base instanceof JSONArray) { int i=(int)num(key); return deepCopy(((JSONArray)base).opt(i)); }
                return JSONObject.NULL;
            }
            case "coalesce": {
                if (args == null) return JSONObject.NULL;
                for(int i=0;i<args.length();i++){ Object v=eval(args.opt(i)); if(!isNull(v) && !text(v).trim().isEmpty()) return v; }
                return JSONObject.NULL;
            }
            case "array": {
                JSONArray out = new JSONArray(); if(args!=null) for(int i=0;i<args.length();i++) out.put(deepCopy(eval(args.opt(i)))); return out;
            }
            case "object": {
                JSONObject src = x.optJSONObject("value"); if(src==null) return new JSONObject(); JSONObject out=new JSONObject(); java.util.Iterator<String> it=src.keys();
                while(it.hasNext()){String k=it.next(); try{out.put(k,deepCopy(eval(src.opt(k))));}catch(Exception ignored){}} return out;
            }
            default: throw new IllegalArgumentException("Desteklenmeyen ifade op: " + op);
        }
    }

    JSONArray filter(JSONArray input, Object predicate, String itemVar) {
        JSONArray out = new JSONArray(); if(input==null) return out;
        Object old = get(itemVar); boolean had = !isNull(old);
        for(int i=0;i<input.length();i++){
            set(itemVar, input.opt(i));
            if(truthy(eval(predicate))) out.put(deepCopy(input.opt(i)));
        }
        if(had) set(itemVar,old); else removeTop(itemVar);
        return out;
    }

    JSONArray dedupe(JSONArray input, Object keyExpr, String itemVar) {
        JSONArray out=new JSONArray(); Set<String> seen=new HashSet<>(); if(input==null)return out;
        Object old=get(itemVar); boolean had=!isNull(old);
        for(int i=0;i<input.length();i++){
            Object v=input.opt(i); set(itemVar,v); String key=canonical(eval(keyExpr));
            if(seen.add(key)) out.put(deepCopy(v));
        }
        if(had)set(itemVar,old);else removeTop(itemVar); return out;
    }

    JSONArray sort(JSONArray input, Object keyExpr, String itemVar, boolean asc) {
        ArrayList<Object> list=new ArrayList<>(); if(input!=null)for(int i=0;i<input.length();i++)list.add(deepCopy(input.opt(i)));
        final Object old=get(itemVar); final boolean had=!isNull(old);
        Collections.sort(list,new Comparator<Object>(){ public int compare(Object a,Object b){ set(itemVar,a);Object ka=eval(keyExpr);set(itemVar,b);Object kb=eval(keyExpr);int c=AgentVm.this.compare(ka,kb);return asc?c:-c; }});
        if(had)set(itemVar,old);else removeTop(itemVar); JSONArray out=new JSONArray();for(Object v:list)out.put(v);return out;
    }

    JSONArray join(JSONArray left, JSONArray right, Object leftKey, Object rightKey, String leftVar, String rightVar, boolean leftJoin) {
        JSONArray out=new JSONArray(); if(left==null) return out; if(right==null) right=new JSONArray();
        Object oldL=get(leftVar), oldR=get(rightVar); boolean hadL=!isNull(oldL), hadR=!isNull(oldR);
        for(int i=0;i<left.length();i++){
            Object lv=left.opt(i); set(leftVar,lv); String lk=canonical(eval(leftKey)); boolean matched=false;
            for(int j=0;j<right.length();j++){
                Object rv=right.opt(j); set(rightVar,rv); String rk=canonical(eval(rightKey));
                if(lk.equals(rk)){ matched=true; JSONObject row=new JSONObject(); try{row.put("left",deepCopy(lv));row.put("right",deepCopy(rv));}catch(Exception ignored){} out.put(row); }
            }
            if(!matched&&leftJoin){JSONObject row=new JSONObject();try{row.put("left",deepCopy(lv));row.put("right",JSONObject.NULL);}catch(Exception ignored){}out.put(row);}
        }
        if(hadL)set(leftVar,oldL);else removeTop(leftVar); if(hadR)set(rightVar,oldR);else removeTop(rightVar); return out;
    }

    static boolean truthy(Object v) {
        if(isNull(v)) return false; if(v instanceof Boolean)return (Boolean)v; if(v instanceof Number)return Math.abs(((Number)v).doubleValue())>1e-12;
        if(v instanceof JSONArray)return ((JSONArray)v).length()>0; if(v instanceof JSONObject)return ((JSONObject)v).length()>0;
        String s=String.valueOf(v).trim(); return !s.isEmpty()&&!"false".equalsIgnoreCase(s)&&!"0".equals(s);
    }

    static String text(Object v){ return isNull(v)?"":String.valueOf(v); }
    static boolean isNull(Object v){ return v==null||v==JSONObject.NULL; }

    private Object arg(JSONArray a,int i){ return a==null?JSONObject.NULL:a.opt(i); }
    private double num(Object raw){ Object v=raw instanceof JSONObject?eval(raw):raw; if(v instanceof Number)return ((Number)v).doubleValue(); String s=text(v).trim().replace(" ","");
        if(s.matches("[-+]?[0-9]{1,3}(\\.[0-9]{3})+(,[0-9]+)?"))s=s.replace(".","").replace(',', '.'); else if(s.matches("[-+]?[0-9]+,[0-9]+"))s=s.replace(',', '.'); else s=s.replaceAll("[^0-9+\\-.,]","").replace(',', '.');
        if(s.isEmpty()||"-".equals(s)||"+".equals(s))return 0d; try{return Double.parseDouble(s);}catch(Exception e){return 0d;} }
    private double numericFold(JSONArray a,double init,char op){double r=init;if(a==null)return r;for(int i=0;i<a.length();i++){double n=num(eval(a.opt(i)));r=op=='+'?r+n:r*n;}return r;}
    private double numericMinMax(JSONArray a,boolean min){if(a==null||a.length()==0)return 0d;double r=num(eval(a.opt(0)));for(int i=1;i<a.length();i++){double n=num(eval(a.opt(i)));r=min?Math.min(r,n):Math.max(r,n);}return r;}
    private boolean equalsLoose(Object a,Object b){if(isNull(a)&&isNull(b))return true;if(a instanceof Number||b instanceof Number)return Math.abs(num(a)-num(b))<1e-9;return norm(text(a)).equals(norm(text(b)));}
    private int compare(Object a,Object b){if((a instanceof Number)||(b instanceof Number))return Double.compare(num(a),num(b));return norm(text(a)).compareTo(norm(text(b)));}
    private String canonical(Object v){if(v instanceof JSONObject||v instanceof JSONArray)return String.valueOf(v);return norm(text(v));}
    private static String norm(String s){return SafetyPolicy.normalize(s==null?"":s);}
    private void removeTop(String path){if(path!=null&&!path.contains("."))vars.remove(path);}
    private static boolean isInt(String s){try{Integer.parseInt(s);return true;}catch(Exception e){return false;}}
    private static Object deepCopy(Object v){if(v==null||v==JSONObject.NULL)return JSONObject.NULL;try{if(v instanceof JSONObject)return new JSONObject(v.toString());if(v instanceof JSONArray)return new JSONArray(v.toString());}catch(Exception ignored){}return v;}

    static boolean selfTest(){
        try{
            AgentVm vm=new AgentVm(); vm.set("a",5); vm.set("obj.x","Merhaba");
            if(Math.abs(((Number)vm.eval(new JSONObject("{\"op\":\"add\",\"args\":[{\"var\":\"a\"},7]}"))).doubleValue()-12)>0.001)return false;
            if(!"Merhaba".equals(vm.get("obj.x")))return false;
            JSONArray rows=new JSONArray("[{\"id\":1,\"p\":20},{\"id\":2,\"p\":10},{\"id\":1,\"p\":30}]");
            JSONArray d=vm.dedupe(rows,new JSONObject("{\"op\":\"get\",\"args\":[{\"var\":\"it\"},\"id\"]}"),"it"); if(d.length()!=2)return false;
            JSONArray s=vm.sort(rows,new JSONObject("{\"op\":\"get\",\"args\":[{\"var\":\"it\"},\"p\"]}"),"it",true); if(s.optJSONObject(0).optInt("p")!=10)return false;
            return true;
        }catch(Exception e){return false;}
    }
}
