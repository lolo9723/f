package tr.edu.balikesir.anketrapor;

import org.json.JSONArray;
import org.json.JSONObject;

/** Gerçek kullanıcı sınıfına benzeyen çok kaynaklı dataset/hesaplama senaryosunu sınar. */
final class AgentComplexSelfTest {
    private AgentComplexSelfTest() {}

    static boolean run() {
        try {
            AgentVm vm = new AgentVm();
            JSONArray ratings = new JSONArray()
                    .put(new JSONObject().put("Otel", "Mavi Deniz Hotel").put("Puan", 4.7).put("Yorum", 812).put("GoogleLink", "https://example.com/g1"))
                    .put(new JSONObject().put("Otel", "Palm Resort").put("Puan", 4.8).put("Yorum", 420).put("GoogleLink", "https://example.com/g2"))
                    .put(new JSONObject().put("Otel", "Liman Spa").put("Puan", 4.6).put("Yorum", 1500).put("GoogleLink", "https://example.com/g3"));
            JSONArray rooms = new JSONArray()
                    .put(new JSONObject().put("Otel", "Mavi Deniz Hotel").put("Kuvet", "bathtub").put("Gecelik", 7200).put("Gece", 3).put("RezLink", "https://example.com/r1"))
                    .put(new JSONObject().put("Otel", "Palm Resort").put("Kuvet", "küvet").put("Gecelik", 6000).put("Gece", 3).put("RezLink", "https://example.com/r2"))
                    .put(new JSONObject().put("Otel", "Liman Spa").put("Kuvet", "duş").put("Gecelik", 5000).put("Gece", 3).put("RezLink", "https://example.com/r3"));

            JSONObject leftName = new JSONObject("{\"op\":\"get\",\"args\":[{\"var\":\"left\"},\"Otel\"]}");
            JSONObject rightName = new JSONObject("{\"op\":\"get\",\"args\":[{\"var\":\"right\"},\"Otel\"]}");
            JSONArray joined = vm.join(ratings, rooms, leftName, rightName, "left", "right", false);
            if (joined.length() != 3) return false;
            vm.set("joined", joined);

            Object predicate = new JSONObject("{\"op\":\"and\",\"args\":[" +
                    "{\"op\":\"gte\",\"args\":[{\"op\":\"get\",\"args\":[{\"op\":\"get\",\"args\":[{\"var\":\"row\"},\"left\"]},\"Puan\"]},4.6]}," +
                    "{\"op\":\"gte\",\"args\":[{\"op\":\"get\",\"args\":[{\"op\":\"get\",\"args\":[{\"var\":\"row\"},\"left\"]},\"Yorum\"]},500]}," +
                    "{\"op\":\"contains\",\"args\":[{\"op\":\"get\",\"args\":[{\"op\":\"get\",\"args\":[{\"var\":\"row\"},\"right\"]},\"Kuvet\"]},\"tub\"]}," +
                    "{\"op\":\"lte\",\"args\":[{\"op\":\"mul\",\"args\":[{\"op\":\"get\",\"args\":[{\"op\":\"get\",\"args\":[{\"var\":\"row\"},\"right\"]},\"Gecelik\"]},{\"op\":\"get\",\"args\":[{\"op\":\"get\",\"args\":[{\"var\":\"row\"},\"right\"]},\"Gece\"]}]},25000]}]}");
            JSONArray filtered = vm.filter(joined, predicate, "row");
            if (filtered.length() != 1) return false;
            JSONObject only = filtered.getJSONObject(0);
            if (!"Mavi Deniz Hotel".equals(only.getJSONObject("left").getString("Otel"))) return false;

            vm.set("otel", only.getJSONObject("left"));
            String q = AgentTemplateResolver.interpolate("${otel.Otel} 28 Ağustos 3 gece küvet", vm);
            if (!q.startsWith("Mavi Deniz Hotel")) return false;

            if (Math.abs(WebResearchActivity.parseNumber("1,2 bin değerlendirme") - 1200d) > .01) return false;
            if (Math.abs(WebResearchActivity.parseNumber("4,7 / 5") - 4.7d) > .01) return false;
            return true;
        } catch (Exception e) { return false; }
    }
}
