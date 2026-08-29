package com.videofabrikasi.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

public final class KaggleClient {
    // Official current SDK endpoint: https://api.kaggle.com/v1/<service>/<method>
    static final String RPC = "https://api.kaggle.com/v1";
    static final String REST = "https://api.kaggle.com/api/v1";

    public static final class Result {
        public final int code;
        public final String body;
        public final String location;
        Result(int code, String body, String location) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.location = location == null ? "" : location;
        }
        public boolean ok() { return code >= 200 && code < 300; }
        public boolean redirect() { return code >= 300 && code < 400 && !location.isEmpty(); }
    }

    public static final class AccountIdentity {
        public final boolean active;
        public final String username;
        AccountIdentity(boolean active, String username) {
            this.active = active;
            this.username = username == null ? "" : username.trim();
        }
    }

    public static final class PushResult {
        public final int version;
        public final String url;
        public final String ref;
        PushResult(int version, String url, String ref) {
            this.version = version;
            this.url = url;
            this.ref = ref;
        }
    }

    public static final class DownloadTarget {
        public final String url;
        public final boolean authRequired;
        DownloadTarget(String url, boolean authRequired) {
            this.url = requireHttpsUrl(url);
            this.authRequired = authRequired;
        }
    }

    public AccountIdentity introspectToken(String token) throws Exception {
        String value = token == null ? "" : token.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Kaggle token boş.");
        JSONObject body = new JSONObject();
        body.put("token", value);
        Result r = request(
                "POST",
                "https://www.kaggle.com/api/v1/oauth2/introspect",
                null,
                body.toString(),
                true);
        if (!r.ok()) {
            throw new IllegalStateException("Kaggle token introspection HTTP " + r.code + ": " + compact(r.body));
        }
        return accountIdentityFromIntrospectionJson(r.body);
    }

    static AccountIdentity accountIdentityFromIntrospectionJson(String jsonText) throws Exception {
        JSONObject j = new JSONObject(jsonText == null ? "{}" : jsonText);
        return new AccountIdentity(j.optBoolean("active", false), j.optString("username", ""));
    }

    static String tokenFromImportedText(String raw) throws Exception {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return "";

        // Current Kaggle API tokens are opaque values. The official SDK accepts the
        // KAGGLE_API_TOKEN/access_token contents as-is and does not require a KGAT_ prefix.
        // A plain clipboard/file token is therefore accepted here and then cryptographically
        // validated by Kaggle introspection before it is ever persisted in SecureStore.
        if (!text.startsWith("{") && !text.matches(".*\\s+.*")) return text;

        try {
            JSONObject j = new JSONObject(text);
            for (String key : new String[]{"token", "access_token", "api_token"}) {
                String candidate = j.optString(key, "").trim();
                if (!candidate.isEmpty() && !candidate.matches(".*\\s+.*")) return candidate;
            }
        } catch (Exception ignored) {}

        // Keep support for a KGAT_ token embedded in copied explanatory text.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(KGAT_[A-Za-z0-9._\\-]+)")
                .matcher(text);
        return m.find() ? m.group(1) : "";
    }

    public Result validateToken(String token) throws Exception {
        JSONObject body = new JSONObject();
        body.put("group", "PROFILE");
        body.put("pageSize", 1);
        Result r = rpc("ListKernels", token, body, true);
        if (r.ok()) return r;

        // Compatibility fallback for legacy Kaggle API routing.
        return request("GET", REST + "/kernels/list?group=profile&page_size=1", token, null, true);
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

        Result r = rpc("SaveKernel", token, body, true);
        if (!r.ok()) {
            r = request("POST", REST + "/kernels/push", token, body.toString(), true);
        }
        if (!r.ok()) throw new IllegalStateException("Kaggle push HTTP " + r.code + ": " + compact(r.body));
        JSONObject json = new JSONObject(r.body);
        String error = json.optString("error", "");
        if (!error.isEmpty()) throw new IllegalStateException("Kaggle: " + error);
        int version = json.optInt("versionNumber", json.optInt("version_number", 0));
        String responseRef = json.optString("ref", username + "/" + slug);
        String responseUrl = json.optString("url", "");
        if (version <= 0) {
            // A successful SaveKernel should return a concrete version. Treat a missing
            // version as an incomplete push rather than pretending the job exists.
            throw new IllegalStateException("Kaggle kernel oluşturuldu fakat sürüm numarası dönmedi.");
        }
        return new PushResult(version, responseUrl, responseRef);
    }

    public String getStatus(String username, String slug, String token) throws Exception {
        JSONObject body = new JSONObject();
        body.put("userName", username);
        body.put("kernelSlug", slug);
        Result r = rpc("GetKernelSessionStatus", token, body, true);
        if (!r.ok()) {
            String q = "?userName=" + enc(username) + "&kernelSlug=" + enc(slug);
            r = request("GET", REST + "/kernels/status" + q, token, null, true);
        }
        if (!r.ok()) throw new IllegalStateException("Durum HTTP " + r.code + ": " + compact(r.body));
        JSONObject j = new JSONObject(r.body);
        String failure = j.optString("failureMessage", j.optString("failure_message", ""));
        if (!failure.isEmpty()) return "HATALI: " + failure;
        return normalizeStatus(j.optString("status", ""));
    }

    public String getAcceleratorQuotaSummary(String token) throws Exception {
        Result r = rpc("GetAcceleratorQuotaStatistics", token, new JSONObject(), true);
        if (!r.ok()) return "GPU kota sorgusu HTTP " + r.code + ": " + compact(r.body);
        try {
            JSONObject j = new JSONObject(r.body);
            JSONObject gpu = j.optJSONObject("gpuQuota");
            if (gpu == null) gpu = j.optJSONObject("gpu_quota");
            if (gpu == null) return "GPU kota bilgisi dönmedi.";
            String used = String.valueOf(gpu.opt("timeUsed"));
            if ("null".equals(used)) used = String.valueOf(gpu.opt("time_used"));
            String reserved = String.valueOf(gpu.opt("timeReserved"));
            if ("null".equals(reserved)) reserved = String.valueOf(gpu.opt("time_reserved"));
            String allowed = String.valueOf(gpu.opt("totalTimeAllowed"));
            if ("null".equals(allowed)) allowed = String.valueOf(gpu.opt("total_time_allowed"));
            return "used=" + used + ", reserved=" + reserved + ", allowed=" + allowed;
        } catch (Exception e) {
            return "GPU kota yanıtı: " + compactWide(r.body, 800);
        }
    }

    public String getFailureDiagnostics(String username, String slug, String token) {
        StringBuilder out = new StringBuilder();
        try {
            JSONObject body = new JSONObject();
            body.put("userName", username);
            body.put("kernelSlug", slug);
            body.put("pageSize", 100);
            Result r = rpc("ListKernelSessionOutput", token, body, true);
            if (!r.ok()) {
                return "Kaggle çıktı/log sorgusu HTTP " + r.code + ": " + compactWide(r.body, 1200);
            }
            JSONObject j = new JSONObject(r.body);
            String log = j.optString("log", "");
            String logSummary = diagnosticLogSummary(log);
            if (!logSummary.isEmpty()) {
                out.append("Kaggle log özeti:\n").append(logSummary);
            }

            JSONArray files = j.optJSONArray("files");
            if (files != null) {
                for (String wanted : new String[]{"status.json", "ai_error.txt"}) {
                    String url = "";
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject item = files.optJSONObject(i);
                        if (item == null) continue;
                        String name = item.optString("fileName", item.optString("file_name", ""));
                        if (wanted.equals(name)) {
                            url = item.optString("url", "");
                            break;
                        }
                    }
                    if (!url.isEmpty()) {
                        try {
                            Result file = request("GET", requireHttpsUrl(url), null, null, true);
                            if (file.ok()) {
                                if (out.length() > 0) out.append("\n");
                                if ("status.json".equals(wanted)) {
                                    JSONObject status = new JSONObject(file.body);
                                    out.append("status.json: stage=")
                                            .append(status.optString("stage", ""))
                                            .append(", error=")
                                            .append(compactWide(status.optString("error", ""), 1200));
                                } else {
                                    out.append("ai_error.txt:\n")
                                        .append(diagnosticLogSummary(file.body));
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            if (out.length() > 0) out.append("\n");
            out.append("Tanılama alınamadı: ").append(compactWide(e.getMessage(), 700));
        }
        String result = out.toString().trim();
        return result.isEmpty() ? "Kaggle ayrıntılı log/çıktı döndürmedi." : compactWide(result, 9000);
    }

    static String diagnosticLogSummary(String raw) {
        String text = raw == null ? "" : raw.replace("\r", "");
        if (text.trim().isEmpty()) return "";

        // ai_error.txt is plain Python traceback text. Preserve its stack frames
        // instead of filtering away the crucial "File ..., line ..." locations.
        int traceback = text.lastIndexOf("Traceback (most recent call last):");
        if (traceback >= 0 && !text.contains("\"stream_name\"")) {
            return compactWide(text.substring(traceback).trim(), 6500);
        }

        String[] lines = text.split("\n");
        StringBuilder important = new StringBuilder();
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.US);
            String trimmed = line.trim();
            if (lower.contains("traceback") || lower.contains("error") || lower.contains("exception")
                    || lower.contains("failed") || lower.contains("cuda") || lower.contains("out of memory")
                    || lower.contains("no space") || lower.contains("modulenotfound")
                    || lower.contains("importerror") || lower.contains("runtimeerror")
                    || lower.contains("valueerror") || lower.contains("assertionerror")
                    || lower.contains("killed") || lower.contains("terminated")
                    || trimmed.startsWith("File ") || trimmed.startsWith("at ")) {
                important.append(trimmed).append('\n');
            }
        }
        String chosen = important.toString().trim();
        if (chosen.isEmpty()) {
            int start = Math.max(0, lines.length - 45);
            StringBuilder tail = new StringBuilder();
            for (int i = start; i < lines.length; i++) tail.append(lines[i]).append('\n');
            chosen = tail.toString().trim();
        }
        return compactWide(chosen, 6500);
    }

    private static String compactWide(String s, int limit) {
        if (s == null) return "";
        String x = s.trim();
        if (x.length() <= limit) return x;
        return "…" + x.substring(x.length() - limit);
    }

    public DownloadTarget resolveOutputDownload(String username, String slug, int version,
                                                 String filePath, String token) throws Exception {
        // Primary path mirrors the current official Kaggle CLI: list session outputs
        // and use the exact signed URL returned for the requested file.
        Exception listFailure = null;
        try {
            DownloadTarget listed = resolveFromOutputList(username, slug, filePath, token);
            if (listed != null) return listed;
        } catch (Exception e) {
            listFailure = e;
        }

        // Fallback: official DownloadKernelOutput RPC for a specific version/file.
        if (version <= 0) version = getCurrentVersion(username, slug, token);
        JSONObject body = new JSONObject();
        body.put("ownerSlug", username);
        body.put("kernelSlug", slug);
        body.put("versionNumber", version);
        body.put("filePath", filePath);

        Result r = rpc("DownloadKernelOutput", token, body, false);
        if (r.redirect()) return new DownloadTarget(r.location, false);
        if (r.ok()) {
            try {
                JSONObject j = new JSONObject(r.body);
                String url = j.optString("url", "");
                if (!url.isEmpty()) return new DownloadTarget(url, false);
            } catch (Exception ignored) {}
        }
        String detail = listFailure == null ? "" : " Listeleme: " + compact(listFailure.getMessage());
        throw new IllegalStateException("Kaggle çıktısı bulunamadı: " + filePath + ". HTTP " + r.code
                + ": " + compact(r.body) + detail);
    }

    private DownloadTarget resolveFromOutputList(String username, String slug, String filePath, String token) throws Exception {
        String pageToken = "";
        for (int page = 0; page < 20; page++) {
            JSONObject body = new JSONObject();
            body.put("userName", username);
            body.put("kernelSlug", slug);
            body.put("pageSize", 100);
            if (!pageToken.isEmpty()) body.put("pageToken", pageToken);
            Result r = rpc("ListKernelSessionOutput", token, body, true);
            if (!r.ok()) throw new IllegalStateException("Çıktı listesi HTTP " + r.code + ": " + compact(r.body));

            DownloadTarget found = outputTargetFromListJson(r.body, filePath);
            if (found != null) return found;

            JSONObject j = new JSONObject(r.body);
            pageToken = j.optString("nextPageToken", j.optString("next_page_token", ""));
            if (pageToken.isEmpty()) return null;
        }
        throw new IllegalStateException("Kaggle çıktı listesi 20 sayfayı aştı; güvenlik sınırı durdurdu.");
    }

    static DownloadTarget outputTargetFromListJson(String jsonText, String wantedFile) throws Exception {
        JSONObject j = new JSONObject(jsonText == null ? "{}" : jsonText);
        JSONArray files = j.optJSONArray("files");
        if (files == null) return null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            String fileName = f.optString("fileName", f.optString("file_name", ""));
            if (!wantedFile.equals(fileName)) continue;
            String url = f.optString("url", "");
            if (url.isEmpty()) throw new IllegalStateException("Kaggle çıktı kaydı URL içermiyor: " + wantedFile);
            return new DownloadTarget(url, false);
        }
        return null;
    }

    public String getOutputState(String username, String slug, int version, String token) throws Exception {
        DownloadTarget t = resolveOutputDownload(username, slug, version, "status.json", token);
        Result r = request("GET", t.url, t.authRequired ? token : null, null, true);
        if (!r.ok()) throw new IllegalStateException("status.json HTTP " + r.code);
        return outputStateFromJson(r.body);
    }

    static String outputStateFromJson(String jsonText) throws Exception {
        JSONObject j = new JSONObject(jsonText == null ? "{}" : jsonText);
        boolean aiOk = j.optBoolean("ai_ok", false);
        String stage = j.optString("stage", "").toUpperCase(Locale.US);
        String error = compact(j.optString("error", ""));
        if (aiOk && stage.equals("COMPLETE")) return "AI TAMAMLANDI";
        if (stage.contains("FALLBACK") || (!aiOk && stage.contains("COMPLETE"))) {
            return error.isEmpty() ? "AI BAŞARISIZ — FALLBACK" : "AI BAŞARISIZ — FALLBACK: " + error;
        }
        if (stage.contains("FAIL") || stage.contains("ERROR")) {
            return error.isEmpty() ? "AI HATALI" : "AI HATALI: " + error;
        }
        return stage.isEmpty() ? "ÇIKTI DURUMU BİLİNMİYOR" : stage;
    }

    private int getCurrentVersion(String username, String slug, String token) throws Exception {
        JSONObject body = new JSONObject();
        body.put("userName", username);
        body.put("kernelSlug", slug);
        Result r = rpc("GetKernel", token, body, true);
        if (!r.ok()) throw new IllegalStateException("Kaggle sürümü alınamadı. HTTP " + r.code);
        JSONObject j = new JSONObject(r.body);
        JSONObject metadata = j.optJSONObject("metadata");
        int v = metadata == null ? 0 : metadata.optInt("currentVersionNumber",
                metadata.optInt("current_version_number", 0));
        if (v <= 0) throw new IllegalStateException("Geçerli Kaggle sürüm numarası bulunamadı.");
        return v;
    }

    private Result rpc(String method, String token, JSONObject body, boolean followRedirects) throws Exception {
        return request("POST", RPC + "/kernels.KernelsApiService/" + method,
                token, body == null ? "{}" : body.toString(), followRedirects);
    }

    public static String slugify(String input) {
        String x = input == null ? "" : input;
        x = x.replace('ı','i').replace('İ','I').replace('ş','s').replace('Ş','S')
                .replace('ğ','g').replace('Ğ','G').replace('ü','u').replace('Ü','U')
                .replace('ö','o').replace('Ö','O').replace('ç','c').replace('Ç','C');
        x = Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.US);
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
        if (s.contains("NEW_SCRIPT")) return "KUYRUKTA";
        if (s.isEmpty()) return "BİLİNMİYOR";
        return s;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
    }

    private static String compact(String s) {
        if (s == null) return "";
        String x = s.replaceAll("\\s+", " ").trim();
        return x.length() > 220 ? x.substring(0, 220) + "…" : x;
    }

    static String requireHttpsUrl(String raw) {
        try {
            URI u = URI.create(raw == null ? "" : raw.trim());
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getHost().isEmpty()) {
                throw new IllegalArgumentException("Güvensiz veya geçersiz indirme URL'si.");
            }
            if (u.getUserInfo() != null) throw new IllegalArgumentException("URL kullanıcı bilgisi içeremez.");
            return u.toString();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Güvensiz veya geçersiz indirme URL'si.", e);
        }
    }

    public Result request(String method, String url, String token, String json, boolean followRedirects) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(followRedirects);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json, application/zip, video/mp4, */*");
        c.setRequestProperty("User-Agent", "kaggle-api/v1.7.0 VideoFabrikasiAndroid/1.0");
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
