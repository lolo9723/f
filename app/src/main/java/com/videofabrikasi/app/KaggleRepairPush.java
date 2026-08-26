package com.videofabrikasi.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/**
 * Isolated SaveKernel path for cross-run scene repair.
 * It attaches one exact prior kernel version as a kernel data source without
 * changing the proven normal KaggleClient.pushKernel path.
 */
final class KaggleRepairPush {
    private KaggleRepairPush() {}

    static String sourceRef(String username, String slug, int version) {
        String u = safePart(username, "username");
        String s = safePart(slug, "slug");
        if (version <= 0) throw new IllegalArgumentException("Source kernel version must be > 0");
        return u + "/" + s + "/" + version;
    }

    static KaggleClient.PushResult push(
            KaggleClient client,
            String username,
            String slug,
            String title,
            String python,
            String token,
            String sourceUsername,
            String sourceSlug,
            int sourceVersion) throws Exception {
        String source = sourceRef(sourceUsername, sourceSlug, sourceVersion);
        JSONObject body = new JSONObject();
        body.put("slug", safePart(username, "username") + "/" + safePart(slug, "slug"));
        body.put("newTitle", title);
        body.put("text", python);
        body.put("language", "python");
        body.put("kernelType", "script");
        body.put("isPrivate", true);
        body.put("enableGpu", true);
        body.put("enableTpu", false);
        body.put("enableInternet", true);
        body.put("machineShape", "NvidiaTeslaT4");
        body.put("sessionTimeoutSeconds", 10800);
        body.put("datasetDataSources", new JSONArray());
        body.put("competitionDataSources", new JSONArray());
        body.put("kernelDataSources", new JSONArray().put(source));
        body.put("modelDataSources", new JSONArray());

        KaggleClient.Result r = client.request(
                "POST", KaggleClient.RPC + "/kernels.KernelsApiService/SaveKernel",
                token, body.toString(), true);
        if (!r.ok()) {
            r = client.request("POST", KaggleClient.REST + "/kernels/push",
                    token, body.toString(), true);
        }
        if (!r.ok()) throw new IllegalStateException(
                "Kaggle repair push HTTP " + r.code + ": " + compact(r.body));
        JSONObject json = new JSONObject(r.body);
        String error = json.optString("error", "");
        if (!error.isEmpty()) throw new IllegalStateException("Kaggle repair: " + error);
        int version = json.optInt("versionNumber", json.optInt("version_number", 0));
        if (version <= 0) throw new IllegalStateException(
                "Repair kernel created but Kaggle returned no version number");
        String responseRef = json.optString("ref", username + "/" + slug);
        String responseUrl = json.optString("url", "");
        return new KaggleClient.PushResult(version, responseUrl, responseRef);
    }

    private static String safePart(String raw, String label) {
        String x = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if (!x.matches("[a-z0-9][a-z0-9_-]{0,79}")) {
            throw new IllegalArgumentException("Invalid Kaggle " + label + " for repair source");
        }
        return x;
    }

    private static String compact(String value) {
        String x = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return x.length() > 220 ? x.substring(0, 220) + "…" : x;
    }
}
