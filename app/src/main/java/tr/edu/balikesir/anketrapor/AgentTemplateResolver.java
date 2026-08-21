package tr.edu.balikesir.anketrapor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** AGENT görevlerindeki ${degisken.yolu} şablonlarını VM değerleriyle güvenli biçimde çözer. */
final class AgentTemplateResolver {
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([A-Za-z0-9_.$\\-]+)\\}");
    private AgentTemplateResolver() {}

    static String interpolate(String input, AgentVm vm) {
        if (input == null || input.isEmpty() || vm == null) return input == null ? "" : input;
        Matcher m = TOKEN.matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String path = m.group(1);
            Object value = vm.get(path);
            String replacement = AgentVm.text(value);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    static JSONObject resolveObject(JSONObject source, AgentVm vm) {
        if (source == null) return new JSONObject();
        Object v = resolveAny(source, vm);
        return v instanceof JSONObject ? (JSONObject) v : new JSONObject();
    }

    static Object resolveAny(Object value, AgentVm vm) {
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL;
        if (value instanceof String) return interpolate((String) value, vm);
        if (value instanceof JSONArray) {
            JSONArray src = (JSONArray) value, out = new JSONArray();
            for (int i = 0; i < src.length(); i++) out.put(resolveAny(src.opt(i), vm));
            return out;
        }
        if (value instanceof JSONObject) {
            JSONObject src = (JSONObject) value, out = new JSONObject();
            Iterator<String> it = src.keys();
            while (it.hasNext()) {
                String k = it.next();
                try { out.put(k, resolveAny(src.opt(k), vm)); } catch (Exception ignored) {}
            }
            return out;
        }
        return value;
    }

    static boolean selfTest() {
        try {
            AgentVm vm = new AgentVm();
            vm.set("otel.ad", "Deniz & Spa");
            vm.set("gece", 3);
            String s = interpolate("${otel.ad} ${gece} gece", vm);
            if (!"Deniz & Spa 3 gece".equals(s)) return false;
            JSONObject src = new JSONObject("{\"queries\":[\"${otel.ad} küvet\"],\"n\":3}");
            JSONObject r = resolveObject(src, vm);
            return "Deniz & Spa küvet".equals(r.getJSONArray("queries").getString(0)) && r.getInt("n") == 3;
        } catch (Exception e) { return false; }
    }
}
