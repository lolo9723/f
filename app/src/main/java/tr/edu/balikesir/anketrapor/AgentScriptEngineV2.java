package tr.edu.balikesir.anketrapor;

import org.json.JSONArray;
import org.json.JSONObject;

/** JSON tabanlı, beyaz-listeli genel Agent Script v2 derleyicisi. */
final class AgentScriptEngineV2 {
    private AgentScriptEngineV2() {}

    static final class Plan {
        final String name;
        final JSONArray steps;
        final boolean needsRuntimeClipboard;
        Plan(String name, JSONArray steps, boolean needsRuntimeClipboard) {
            this.name = name; this.steps = steps; this.needsRuntimeClipboard = needsRuntimeClipboard;
        }
    }

    static boolean looksLikeV2(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        return s.startsWith("AGENT/2") || (s.startsWith("{") && s.contains("\"steps\""));
    }

    static Plan parse(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("Görev kodu boş.");
        String s = raw.trim();
        if (s.startsWith("AGENT/2")) s = s.substring("AGENT/2".length()).trim();
        JSONObject root = new JSONObject(s);
        int version = root.optInt("version", 2);
        if (version != 2) throw new IllegalArgumentException("Desteklenmeyen AGENT sürümü: " + version);
        String name = root.optString("name", root.optString("task", "özel görev"));
        JSONArray input = root.optJSONArray("steps");
        if (input == null || input.length() == 0) throw new IllegalArgumentException("steps boş.");
        if (input.length() > 200) throw new IllegalArgumentException("Bir görev en fazla 200 üst-seviye adım içerebilir.");
        CompileResult r = compileSteps(input, 0);
        if (r.steps.length() > 500) throw new IllegalArgumentException("Derlenmiş görev 500 adımdan büyük olamaz.");
        return new Plan(name, r.steps, r.needsClipboard);
    }

    private static CompileResult compileSteps(JSONArray input, int depth) throws Exception {
        if (depth > 5) throw new IllegalArgumentException("İç içe görev derinliği en fazla 5 olabilir.");
        JSONArray out = new JSONArray();
        boolean clipboard = false;
        for (int i = 0; i < input.length(); i++) {
            JSONObject src = input.optJSONObject(i);
            if (src == null) throw new IllegalArgumentException("Adım nesne olmalı: " + (i + 1));
            String op = src.optString("op", "").trim().toLowerCase();
            if (op.isEmpty()) throw new IllegalArgumentException("op eksik: " + (i + 1));
            if ("repeat".equals(op)) {
                int count = src.optInt("count", 1);
                if (count < 0 || count > 20) throw new IllegalArgumentException("repeat count 0-20 olmalı.");
                JSONArray nested = src.optJSONArray("steps");
                if (nested == null) throw new IllegalArgumentException("repeat.steps eksik.");
                CompileResult nr = compileSteps(nested, depth + 1);
                clipboard |= nr.needsClipboard;
                for (int n = 0; n < count; n++) for (int j = 0; j < nr.steps.length(); j++) out.put(new JSONObject(nr.steps.getJSONObject(j).toString()));
                continue;
            }
            JSONObject x = new JSONObject();
            switch (op) {
                case "app.open":
                    x.put("kind", "open_app"); x.put("package", require(src, "package")); break;
                case "url.open":
                    x.put("kind", "open_url"); x.put("url", require(src, "url")); break;
                case "ui.tap":
                    x.put("kind", "tap_any"); x.put("texts", arrayOrSingle(src, "any", "text")); x.put("timeout", bounded(src.optInt("timeout_ms", 10000), 500, 30000)); break;
                case "ui.set_text":
                    x.put("kind", "set_any"); x.put("texts", arrayOrSingle(src, "any", "text")); x.put("timeout", bounded(src.optInt("timeout_ms", 10000), 500, 30000));
                    if ("clipboard".equalsIgnoreCase(src.optString("source"))) { x.put("value_source", "clipboard"); clipboard = true; }
                    else { x.put("value_source", "literal"); x.put("value", src.optString("value", "")); }
                    break;
                case "wait":
                    x.put("kind", "wait"); x.put("ms", bounded(src.optInt("ms", 800), 50, 30000)); break;
                case "back": x.put("kind", "back"); break;
                case "swipe":
                    x.put("kind", "swipe"); x.put("direction", src.optString("direction", "up")); break;
                case "instagram.share_ajan_folder":
                    x.put("kind", "share_ajan_album"); break;
                case "web.search_extract":
                    x.put("kind", "web_research"); x.put("spec", validateWebSpec(src)); break;
                case "stop":
                    x.put("kind", "stop"); x.put("message", src.optString("message", "Görev tamamlandı.")); break;
                default:
                    throw new IllegalArgumentException("Desteklenmeyen op: " + op);
            }
            out.put(x);
        }
        return new CompileResult(out, clipboard);
    }

    private static JSONObject validateWebSpec(JSONObject src) throws Exception {
        JSONObject spec = new JSONObject();
        JSONArray queries = src.optJSONArray("queries");
        if (queries == null || queries.length() == 0 || queries.length() > 20) throw new IllegalArgumentException("web.search_extract queries 1-20 olmalı.");
        spec.put("queries", new JSONArray(queries.toString()));
        spec.put("target_count", bounded(src.optInt("target_count", 10), 1, 50));
        spec.put("max_pages", bounded(src.optInt("max_pages", 40), 1, 120));
        spec.put("filename", safeFilename(src.optString("filename", "Yerel_Ajan_Web_Sonuc.xlsx")));
        spec.put("allowed_domains", copyArray(src.optJSONArray("allowed_domains"), 30));
        spec.put("link_contains", copyArray(src.optJSONArray("link_contains"), 30));
        spec.put("must_contain", copyArray(src.optJSONArray("must_contain"), 30));
        spec.put("must_not_contain", copyArray(src.optJSONArray("must_not_contain"), 30));
        spec.put("allow_search_fallback", src.optBoolean("allow_search_fallback", true));
        spec.put("allow_partial", src.optBoolean("allow_partial", false));
        JSONArray fields = src.optJSONArray("fields");
        if (fields == null) fields = new JSONArray();
        if (fields.length() > 30) throw new IllegalArgumentException("En fazla 30 alan çıkarılabilir.");
        JSONArray cleanFields = new JSONArray();
        for (int i = 0; i < fields.length(); i++) {
            JSONObject f = fields.optJSONObject(i); if (f == null) continue;
            String name = f.optString("name", "Alan" + (i + 1));
            JSONArray regex = f.optJSONArray("regex");
            if (regex == null || regex.length() == 0) throw new IllegalArgumentException(name + " için regex gerekli.");
            JSONObject cf = new JSONObject(); cf.put("name", name); cf.put("regex", copyArray(regex, 12));
            cf.put("type", f.optString("type", "text"));
            if (f.has("min")) cf.put("min", f.getDouble("min"));
            if (f.has("max_exclusive")) cf.put("max_exclusive", f.getDouble("max_exclusive"));
            if (f.has("max")) cf.put("max", f.getDouble("max"));
            cleanFields.put(cf);
        }
        spec.put("fields", cleanFields);
        return spec;
    }

    static JSONObject validateWebSpecForV3(JSONObject src) throws Exception { return validateWebSpec(src); }

    static JSONObject carSpec(String brand, int minYear, int maxKm, int count, String filename) throws Exception {
        JSONObject s = new JSONObject();
        JSONArray q = new JSONArray();
        for (int y = minYear; y <= Math.min(2030, minYear + 5); y++) {
            q.put("site:arabam.com/ilan \"" + brand + "\" \"" + y + "\" \"km\"");
            q.put("site:sahibinden.com/ilan \"" + brand + "\" \"" + y + "\" \"km\"");
        }
        s.put("queries", q); s.put("target_count", Math.max(1, Math.min(20, count))); s.put("max_pages", 80);
        s.put("filename", safeFilename(filename));
        s.put("allowed_domains", new JSONArray().put("arabam.com").put("sahibinden.com"));
        s.put("link_contains", new JSONArray().put("/ilan/"));
        s.put("must_contain", new JSONArray().put(brand));
        JSONArray fields = new JSONArray();
        fields.put(new JSONObject().put("name", "Model Yılı").put("type", "int").put("min", minYear)
                .put("regex", new JSONArray().put("(?iu)(?:Yıl|Model Yılı)\\s*[:\\-]?\\s*(20\\d{2})").put("(?iu)\\b(20\\d{2})\\s+Model\\b")));
        fields.put(new JSONObject().put("name", "Kilometre").put("type", "int").put("max_exclusive", maxKm)
                .put("regex", new JSONArray().put("(?iu)(?:Kilometre|KM)\\s*[:\\-]?\\s*([0-9][0-9.\\s]{1,12})\\s*km?").put("(?iu)\\b([0-9][0-9.\\s]{1,12})\\s*km\\b")));
        fields.put(new JSONObject().put("name", "Fiyat").put("type", "text")
                .put("regex", new JSONArray().put("(?iu)(?:Fiyat)\\s*[:\\-]?\\s*([0-9.]+\\s*TL)").put("(?iu)\\b([0-9.]+\\s*TL)\\b")));
        s.put("fields", fields); return s;
    }

    static boolean selfTest() {
        try {
            String sample = "AGENT/2 {\"version\":2,\"name\":\"t\",\"steps\":[{\"op\":\"app.open\",\"package\":\"com.instagram.android\"},{\"op\":\"repeat\",\"count\":2,\"steps\":[{\"op\":\"wait\",\"ms\":100}]},{\"op\":\"stop\"}]}";
            Plan p = parse(sample);
            if (p.steps.length() != 4) return false;
            JSONObject c = carSpec("Chevrolet", 2021, 100000, 4, "test.xlsx");
            return c.getJSONArray("queries").length() >= 4 && c.getJSONArray("fields").length() == 3;
        } catch (Exception e) { return false; }
    }

    private static JSONArray arrayOrSingle(JSONObject src, String arrayKey, String singleKey) throws Exception {
        JSONArray a = src.optJSONArray(arrayKey); if (a != null && a.length() > 0) return copyArray(a, 30);
        String s = src.optString(singleKey, "").trim(); if (s.isEmpty()) throw new IllegalArgumentException("UI seçicisi eksik."); return new JSONArray().put(s);
    }
    private static JSONArray copyArray(JSONArray a, int max) throws Exception {
        JSONArray out = new JSONArray(); if (a == null) return out; if (a.length() > max) throw new IllegalArgumentException("Liste çok uzun.");
        for (int i = 0; i < a.length(); i++) out.put(a.get(i)); return out;
    }
    private static String require(JSONObject o, String k) { String v = o.optString(k, "").trim(); if (v.isEmpty()) throw new IllegalArgumentException(k + " eksik."); return v; }
    private static int bounded(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static String safeFilename(String s) { if (s == null || s.trim().isEmpty()) s = "Yerel_Ajan_Sonuc.xlsx"; s = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim(); return s.toLowerCase().endsWith(".xlsx") ? s : s + ".xlsx"; }
    private static final class CompileResult { final JSONArray steps; final boolean needsClipboard; CompileResult(JSONArray s, boolean c) { steps = s; needsClipboard = c; } }
}
