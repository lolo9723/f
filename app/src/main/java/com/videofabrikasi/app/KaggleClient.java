package com.videofabrikasi.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class KaggleClient {
    private static final String API = "https://www.kaggle.com/api/v1";

    public static final class Result {
        public final int code;
        public final String body;
        public final String location;
        Result(int code, String body, String location) {
            this.code = code; this.body = body == null ? "" : body;
            this.location = location == null ? "" : location;
        }
        public boolean ok() { return code >= 200 && code < 300; }
    }

    public static final class PushResult {
        public final int version;
        public final String url;
        public final String ref;
        PushResult(int version, String url, String ref) {
            this.version = version; this.url = url; this.ref = ref;
        }
    }

    public Result validateToken(String token) throws Exception {
        return request("GET", API + "/kernels/list?mine=true&page=1&page_size=1", token, null, true);
    }

    public PushResult pushKernel(String username, String slug, String title, String python, String token) throws Exception {
        JSONObject body = new JSONObject();
        body.put("slug", username + "/" + slug);
        body.put("newTitle", title);
        body.put("text", python);
        body.put("language", "python");
        body.put("kernelType", "script");
        body.put("isPrivate", true);
        body.put("enableGpu", true);
        body.put("enableInternet", true);
        body.put("machineShape", "NvidiaTeslaT4");
        body.put("sessionTimeoutSeconds", 10800);
        body.put("datasetDataSources", new JSONArray());
        body.put("competitionDataSources", new JSONArray());
        body.put("kernelDataSources", new JSONArray());
        body.put("modelDataSources", new JSONArray());

        Result r = request("POST", API + "/kernels/push", token, body.toString(), true);
        if (!r.ok()) {
            r = request("POST", API + "/kernels.KernelsApiService/SaveKernel", token, body.toString(), true);
        }
        if (!r.ok()) throw new IllegalStateException("Kaggle push HTTP " + r.code + ": " + compact(r.body));
        JSONObject json = new JSONObject(r.body);
        String error = json.optString("error", "");
        if (!error.isEmpty()) throw new IllegalStateException("Kaggle: " + error);
        return new PushResult(json.optInt("versionNumber", 0),
                json.optString("url", ""), json.optString("ref", username + "/" + slug));
    }

    public String getStatus(String username, String slug, String token) throws Exception {
        JSONObject body = new JSONObject();
        body.put("userName", username);
        body.put("kernelSlug", slug);
        Result r = request("POST", API + "/kernels.KernelsApiService/GetKernelSessionStatus",
                token, body.toString(), true);
        if (!r.ok()) {
            String q = "?userName=" + enc(username) + "&kernelSlug=" + enc(slug);
            r = request("GET", API + "/kernels/status" + q, token, null, true);
        }
        if (!r.ok()) throw new IllegalStateException("Durum HTTP " + r.code + ": " + compact(r.body));
        JSONObject j = new JSONObject(r.body);
        String failure = j.optString("failureMessage", j.optString("failure_message", ""));
        if (!failure.isEmpty()) return "HATALI: " + failure;
        return normalizeStatus(j.optString("status", ""));
    }

    public String finalVideoUrl(String username, String slug) throws Exception {
        return API + "/kernels/output/download/" + encPath(username) + "/" + encPath(slug) + "/FINAL.mp4";
    }

    public static String slugify(String input) {
        String x = input == null ? "" : input.toLowerCase(Locale.US);
        x = x.replace('ı','i').replace('ş','s').replace('ğ','g').replace('ü','u').replace('ö','o').replace('ç','c');
        x = x.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (x.isEmpty()) x = "video";
        if (x.length() > 38) x = x.substring(0, 38).replaceAll("-+$", "");
        return x;
    }

    public static String normalizeStatus(String status) {
        String s = status == null ? "" : status.trim().toUpperCase(Locale.US);
        if (s.contains("COMPLETE")) return "TAMAMLANDI";
        if (s.contains("ERROR") || s.contains("FAIL")) return "HATALI";
        if (s.contains("RUNNING")) return "ÜRETİLİYOR";
        if (s.contains("QUEUE") || s.contains("PENDING")) return "KUYRUKTA";
        if (s.contains("CANCEL")) return "DURDURULDU";
        if (s.isEmpty()) return "BİLİNMİYOR";
        return s;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
    }
    private static String encPath(String s) throws Exception {
        return enc(s).replace("+", "%20");
    }
    private static String compact(String s) {
        if (s == null) return "";
        String x = s.replaceAll("\\s+", " ").trim();
        return x.length() > 220 ? x.substring(0, 220) + "…" : x;
    }

    public Result request(String method, String url, String token, String json, boolean followRedirects) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(followRedirects);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json, application/zip, video/mp4, */*");
        c.setRequestProperty("User-Agent", "VideoFabrikasiAndroid/1.0");
        if (token != null && !token.trim().isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token.trim());
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        String location = c.getHeaderField("Location");
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = readText(in);
        c.disconnect();
        return new Result(code, body, location);
    }

    private static String readText(InputStream in) throws Exception {
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) b.append(line).append('\n');
            return b.toString();
        }
    }
}
