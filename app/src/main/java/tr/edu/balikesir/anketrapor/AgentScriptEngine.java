package tr.edu.balikesir.anketrapor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AgentScriptEngine {
    private AgentScriptEngine() {}

    static final class Plan {
        final String name;
        final JSONArray steps;
        final boolean needsRuntimeClipboard;
        Plan(String name, JSONArray steps, boolean needsRuntimeClipboard) {
            this.name = name; this.steps = steps; this.needsRuntimeClipboard = needsRuntimeClipboard;
        }
    }

    static boolean looksLikeScript(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        return s.startsWith("AGENT/1") || s.contains("TASK:") || s.contains("STEPS:") || s.contains("OPEN_APP") || s.contains("OPEN_URL");
    }

    static Plan parse(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("Görev kodu boş.");
        String script = raw.replace("\r\n", "\n").replace('\r', '\n');
        String task = scalar(script, "TASK", "ozel_gorev");
        if ("chevrolet_ilani_bul".equalsIgnoreCase(task) || script.contains("min_model_year") && script.contains("max_km")) {
            return parseCarSearch(script, task);
        }
        if ("instagram_ajan_album".equalsIgnoreCase(task) || script.contains("album_name: \"Ajan\"") || script.contains("album_name: 'Ajan'")) {
            return parseInstagramAlbum(script, task);
        }
        if (script.trim().startsWith("AGENT/1")) return parseLineDsl(script, task);
        return parseLegacy(script, task);
    }

    private static Plan parseCarSearch(String s, String task) throws Exception {
        String brand = scalar(s, "brand", "Chevrolet");
        int minYear = integer(s, "min_model_year", 2021);
        int maxKm = integer(s, "max_km_exclusive", integer(s, "max_km", 100000));
        int count = integer(s, "count", 4);
        String filename = scalar(s, "filename", brand + "_Arac_Ilani.xlsx");
        String query = brand + " " + minYear + " satılık Türkiye";
        JSONObject step = new JSONObject();
        step.put("kind", "car_search"); step.put("brand", brand); step.put("min_year", minYear); step.put("max_km", maxKm);
        step.put("count", Math.max(1, Math.min(20, count))); step.put("query", query); step.put("filename", filename);
        JSONArray a = new JSONArray(); a.put(step);
        return new Plan(task, a, false);
    }

    private static Plan parseInstagramAlbum(String s, String task) throws Exception {
        JSONArray a = new JSONArray();
        JSONObject share = new JSONObject(); share.put("kind", "share_ajan_album"); share.put("package", "com.instagram.android"); a.put(share);
        JSONObject wait = new JSONObject(); wait.put("kind", "wait"); wait.put("ms", 1800); a.put(wait);
        JSONObject set = new JSONObject(); set.put("kind", "set_any");
        set.put("texts", array("Açıklama yaz...", "Açıklama ekle", "Write a caption...", "Write a caption"));
        set.put("value_source", "clipboard"); set.put("timeout", 12000); a.put(set);
        JSONObject stop = new JSONObject(); stop.put("kind", "stop"); stop.put("message", "Instagram gönderisi hazır. Son Paylaş düğmesi sende."); a.put(stop);
        return new Plan(task, a, true);
    }

    private static Plan parseLineDsl(String s, String task) throws Exception {
        JSONArray out = new JSONArray(); boolean clip = false;
        String[] lines = s.split("\n");
        for (String original : lines) {
            String line = original.trim();
            if (line.isEmpty() || line.startsWith("#") || line.equals("AGENT/1")) continue;
            int sp = line.indexOf(' ');
            String cmd = (sp < 0 ? line : line.substring(0, sp)).trim().toUpperCase(Locale.ROOT);
            String arg = sp < 0 ? "" : line.substring(sp + 1).trim();
            JSONObject x = new JSONObject();
            switch (cmd) {
                case "OPEN_APP": x.put("kind", "open_app"); x.put("package", unquote(arg)); break;
                case "OPEN_URL": x.put("kind", "open_url"); x.put("url", unquote(arg)); break;
                case "GOOGLE_SEARCH": x.put("kind", "google_search"); x.put("query", unquote(arg)); break;
                case "WAIT": x.put("kind", "wait"); x.put("ms", toInt(arg, 800)); break;
                case "TAP_ANY": x.put("kind", "tap_any"); x.put("texts", splitAlternatives(arg)); x.put("timeout", 9000); break;
                case "SET_ANY": {
                    x.put("kind", "set_any");
                    int eq = arg.indexOf('=');
                    String left = eq >= 0 ? arg.substring(0, eq).trim() : "";
                    String right = eq >= 0 ? arg.substring(eq + 1).trim() : arg;
                    x.put("texts", splitAlternatives(left));
                    if ("CLIPBOARD".equalsIgnoreCase(right)) { x.put("value_source", "clipboard"); clip = true; }
                    else { x.put("value_source", "literal"); x.put("value", unquote(right)); }
                    x.put("timeout", 9000); break;
                }
                case "BACK": x.put("kind", "back"); break;
                case "SWIPE_DOWN": x.put("kind", "swipe"); x.put("direction", "down"); break;
                case "SWIPE_UP": x.put("kind", "swipe"); x.put("direction", "up"); break;
                case "STOP": x.put("kind", "stop"); x.put("message", unquote(arg)); break;
                case "AJAN_ALBUM_INSTAGRAM": x.put("kind", "share_ajan_album"); x.put("package", "com.instagram.android"); break;
                default: continue;
            }
            out.put(x);
        }
        if (out.length() == 0) throw new IllegalArgumentException("Desteklenen komut bulunamadı.");
        return new Plan(task, out, clip);
    }

    private static Plan parseLegacy(String s, String task) throws Exception {
        JSONArray out = new JSONArray(); boolean clip = false;
        Map<String,String> variables = new HashMap<>();
        String[] lines = s.split("\n");
        String pending = "";
        JSONArray pendingTexts = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("- READ_CLIPBOARD")) { pending = "read_clipboard"; continue; }
            if (line.startsWith("- OPEN_APP")) { pending = "open_app"; String inline = afterColon(line); if (!inline.isEmpty()) addOpenApp(out, inline); continue; }
            if (line.startsWith("- OPEN:") || line.startsWith("- OPEN_URL")) { String v = afterColon(line); if (!v.isEmpty()) addOpenUrl(out, v); else pending = "open_url"; continue; }
            if (line.startsWith("- GOOGLE_SEARCH")) { pending = "google_search"; continue; }
            if (line.startsWith("- WAIT")) { pending = "wait"; continue; }
            if (line.startsWith("- FIND_AND_TAP")) { pending = "tap"; pendingTexts = null; continue; }
            if (line.startsWith("- FIND:")) { pending = "find"; pendingTexts = null; continue; }
            if (line.startsWith("- SET_TEXT")) { pending = "set"; continue; }
            if (line.startsWith("- STOP_BEFORE") || line.startsWith("- STOP")) { JSONObject x = new JSONObject(); x.put("kind", "stop"); x.put("message", "Görev hazır. Son kritik düğme sende."); out.put(x); pending = "stop"; continue; }
            if (line.startsWith("- GO_BACK") || line.equals("- BACK")) { JSONObject x = new JSONObject(); x.put("kind", "back"); out.put(x); continue; }

            if (line.startsWith("save_as:") && "read_clipboard".equals(pending)) {
                variables.put(unquote(afterColon(line)), "clipboard"); clip = true; pending = ""; continue;
            }
            if ((line.startsWith("package:") || line.startsWith("app:")) && "open_app".equals(pending)) {
                addOpenApp(out, afterColon(line)); pending = ""; continue;
            }
            if (line.startsWith("url:") && "open_url".equals(pending)) { addOpenUrl(out, afterColon(line)); pending = ""; continue; }
            if (line.startsWith("query:") && "google_search".equals(pending)) {
                JSONObject x = new JSONObject(); x.put("kind", "google_search"); x.put("query", unquote(afterColon(line))); out.put(x); pending = ""; continue;
            }
            if (line.startsWith("milliseconds:") && "wait".equals(pending)) {
                JSONObject x = new JSONObject(); x.put("kind", "wait"); x.put("ms", toInt(afterColon(line), 800)); out.put(x); pending = ""; continue;
            }
            if (line.startsWith("text_any:") && ("tap".equals(pending) || "find".equals(pending) || "stop".equals(pending))) {
                pendingTexts = parseArray(afterColon(line));
                if ("tap".equals(pending)) { JSONObject x = new JSONObject(); x.put("kind", "tap_any"); x.put("texts", pendingTexts); x.put("timeout", 10000); out.put(x); pending = ""; }
                continue;
            }
            if (line.startsWith("value:") && "set".equals(pending)) {
                String v = unquote(afterColon(line)); JSONObject x = new JSONObject(); x.put("kind", "set_any");
                x.put("texts", pendingTexts == null ? new JSONArray() : pendingTexts); x.put("timeout", 10000);
                String src = variables.get(v);
                if ("clipboard".equals(src) || "CLIPBOARD".equalsIgnoreCase(v) || "TEXT".equalsIgnoreCase(v) || "CAPTION".equalsIgnoreCase(v)) {
                    x.put("value_source", "clipboard"); clip = true;
                } else { x.put("value_source", "literal"); x.put("value", v); }
                out.put(x); pending = ""; pendingTexts = null; continue;
            }
        }
        if (out.length() == 0) throw new IllegalArgumentException("Görev kodu tanındı ama çalıştırılabilir adım bulunamadı.");
        return new Plan(task, out, clip);
    }

    static boolean selfTest() {
        try {
            Plan a = parse("AGENT/1\nOPEN_APP com.instagram.android\nWAIT 500\nTAP_ANY "+'"'+"Oluştur"+'"'+" || "+'"'+"Create"+'"'+"\nSTOP test");
            if (a.steps.length() != 4) return false;
            Plan b = parse("TASK: chevrolet_ilani_bul\nTARGET:\n count: 4\nCRITERIA:\n brand: Chevrolet\n min_model_year: 2021\n max_km_exclusive: 100000\nOUTPUT:\n filename: test.xlsx");
            return b.steps.length() == 1 && "car_search".equals(b.steps.optJSONObject(0).optString("kind"));
        } catch (Exception e) { return false; }
    }

    private static void addOpenApp(JSONArray out, String v) throws Exception { JSONObject x = new JSONObject(); x.put("kind", "open_app"); x.put("package", unquote(v)); out.put(x); }
    private static void addOpenUrl(JSONArray out, String v) throws Exception { JSONObject x = new JSONObject(); x.put("kind", "open_url"); x.put("url", unquote(v)); out.put(x); }
    private static JSONArray array(String... s) { JSONArray a = new JSONArray(); for (String x : s) a.put(x); return a; }

    private static String scalar(String s, String key, String def) {
        Pattern p = Pattern.compile("(?im)^\\s*" + Pattern.quote(key) + "\\s*:\\s*[\\\"']?([^\\n\\r\\\"']+)");
        Matcher m = p.matcher(s); return m.find() ? m.group(1).trim() : def;
    }
    private static int integer(String s, String key, int def) { return toInt(scalar(s, key, String.valueOf(def)), def); }
    private static int toInt(String s, int def) { try { return Integer.parseInt(s.replaceAll("[^0-9-]", "")); } catch (Exception e) { return def; } }
    private static String afterColon(String s) { int i = s.indexOf(':'); return i < 0 ? "" : s.substring(i + 1).trim(); }
    private static String unquote(String s) {
        if (s == null) return ""; s = s.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) return s.substring(1, s.length() - 1);
        return s;
    }
    private static JSONArray parseArray(String v) {
        JSONArray a = new JSONArray(); if (v == null) return a; String s = v.trim();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        for (String x : s.split(",")) { String q = unquote(x.trim()); if (!q.isEmpty()) a.put(q); }
        return a;
    }
    private static JSONArray splitAlternatives(String v) {
        JSONArray a = new JSONArray(); if (v == null) return a;
        for (String x : v.split("\\|\\|")) { String q = unquote(x.trim()); if (!q.isEmpty()) a.put(q); }
        return a;
    }
}
