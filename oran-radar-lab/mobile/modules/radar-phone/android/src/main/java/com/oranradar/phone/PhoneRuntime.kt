package com.oranradar.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import androidx.work.*
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

object PhoneRuntime {
  private val engineLock = ReentrantLock()
  private val scoreLock = ReentrantLock()
  private val scoreExecutor = Executors.newSingleThreadExecutor()
  @Volatile private var scoreQueued = false
  @Volatile private var scoreNextAt = 0L
  @Volatile private var scorePriority = emptyList<String>()
  @Volatile var scoreRunning = false
  @Volatile var running = false
  @Volatile var foreground = false
  fun prefs(context: Context) = context.getSharedPreferences("radar-phone", Context.MODE_PRIVATE)
  fun root(context: Context) = File(context.filesDir, "radar-phone-data").apply { mkdirs() }
  // Missing flag upgrades Lab 3.0's limited list to automatic discovery.
  fun pairs(context: Context) = if (prefs(context).getBoolean("all_pairs", true)) "[]" else prefs(context).getString("pairs", "[]")!!
  @Synchronized private fun python(context: Context): Python {
    if (!Python.isStarted()) {
      Python.start(AndroidPlatform(context.applicationContext))
      Python.getInstance().getModule("os").get("environ")!!.callAttr("__setitem__", "RADAR_PHONE_SCIENCE", "1")
      Python.getInstance().getModule("os").get("environ")!!.callAttr("__setitem__", "OPENBLAS_NUM_THREADS", "1")
    }
    return Python.getInstance()
  }
  private fun module(context: Context) = python(context).getModule("radar.phone")
  fun request(context: Context, method: String, path: String, query: String, body: String): String {
    if (path == "/v2/control/reconcile" && method == "POST" && !prefs(context).getBoolean("enabled", false)) {
      return JSONObject().put("status", 409).put("body", JSONObject().put("error", "Telefon motoru duraklatılmış. Üstteki karttan Telefonda araştırmayı başlat düğmesine bas.")).put("queued", org.json.JSONArray()).toString()
    }
    if (path == "/v2/control/reconcile" && method == "POST") { schedule(context); PriceFeed.recover(context) }
    val result = module(context).callAttr("request", root(context).path, pairs(context), method, path, query, body).toString()
    val payload = JSONObject(result)
    if ((payload.optJSONArray("queued")?.length() ?: 0) > 0 || path == "/v2/control/reconcile" && method == "POST") enqueue(context)
    if (path == "/v2/control/reconcile" && method == "POST") {
      payload.put("status", 202)
      payload.getJSONObject("body").put("status", "RECOVERY_QUEUED").put("message", "Telefon kontrol ve yedekleme işi kuyruğa alındı; sonraki güncellemede sonuç doğrulanacak.")
    }
    if (method == "GET" && payload.optInt("status") < 400 && (path == "/v2/snapshot" || path == "/v2/audit")) {
      val audit = if (path == "/v2/audit") payload.getJSONObject("body") else payload.getJSONObject("body").getJSONObject("audit")
      val issues = audit.getJSONArray("issues")
      val settings = prefs(context)
      fun issue(code: String) { issues.put(JSONObject().put("pair", JSONObject.NULL).put("code", code)); audit.put("ok", false) }
      if (!settings.getBoolean("enabled", false)) issue("PHONE_PAUSED")
      else if (!periodicScheduled(context)) issue("PHONE_SCHEDULER_MISSING")
      PriceFeed.healthIssue(context)?.let { issue(it) }
      if (!settings.contains("drive_uri")) issue("DRIVE_NOT_SELECTED")
      else if (!settings.getString("backup_error", "").isNullOrEmpty()) issue("DRIVE_BACKUP_FAILED")
      else if (settings.getLong("backup_handoff", 0) == 0L) issue("DRIVE_BACKUP_PENDING")
      else if (System.currentTimeMillis() - settings.getLong("backup_handoff", 0) > 45 * 60_000L) issue("DRIVE_BACKUP_STALE")
      audit.put("scope", audit.optString("scope") + ", phone enabled state, persistent WorkManager schedule, native price feed last observation and Drive provider backup handoff")
    }
    return payload.toString()
  }
  fun connected(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager.activeNetwork ?: return false
    return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
  }
  fun charging(context: Context) = context.getSystemService(BatteryManager::class.java).isCharging
  @Synchronized fun requestScores(context: Context, pairs: List<String>): String {
    require(pairs.distinct().size == pairs.size && pairs.all { it.matches(Regex("[A-Z0-9]{2,20}TRY")) }) { "Farklı TRY pariteleri gerekli." }
    scorePriority = pairs
    return refreshScores(context)
  }
  @Synchronized fun refreshScores(context: Context): String {
    val state = when {
      !prefs(context).getBoolean("enabled", false) -> "PAUSED"
      scoreQueued || scoreRunning -> "RUNNING"
      System.currentTimeMillis() < scoreNextAt -> "THROTTLED"
      else -> "QUEUED"
    }
    if (state == "QUEUED") {
      scoreQueued = true
      scoreExecutor.execute {
        try { scoreBatch(context.applicationContext) }
        finally { scoreQueued = false }
      }
    }
    return JSONObject().put("status", state).put("nextAt", scoreNextAt).toString()
  }
  private fun scoreBatch(context: Context) {
    if (!prefs(context).getBoolean("enabled", false) || System.currentTimeMillis() < scoreNextAt || !scoreLock.tryLock()) return
    scoreRunning = true
    scoreNextAt = System.currentTimeMillis() + 30_000L
    try {
      if (!connected(context)) throw IllegalStateException("İnternet yok; son puanlar korunuyor.")
      val value = JSONObject(module(context).callAttr("cycle", root(context).path, pairs(context), false, true, true, org.json.JSONArray(scorePriority).toString(), 20).toString())
      val rows = value.getJSONArray("results")
      val failures = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("status") == "FAILED" }
      prefs(context).edit().putLong("last_score_cycle", System.currentTimeMillis())
        .putString("score_error", failures.joinToString("; ") { it.optString("pair") + ": " + it.optString("error") }.take(600)).apply()
    } catch (error: Exception) {
      prefs(context).edit().putString("score_error", (error.message ?: "Puan güncellenemedi").take(600)).apply()
    } finally { scoreRunning = false; scoreLock.unlock() }
  }
  fun cycle(context: Context): Boolean {
    if (!prefs(context).getBoolean("enabled", false) || !engineLock.tryLock()) return false
    running = true
    try {
      if (!connected(context)) throw IllegalStateException("İnternet yok; yerel veriler korunuyor, bağlantıda tekrar denenecek.")
      PriceFeed.refreshOnce(context)
      scoreBatch(context)
      val result = module(context).callAttr("cycle", root(context).path, pairs(context), charging(context), false, false).toString()
      val value = JSONObject(result)
      val rows = value.getJSONArray("results")
      val failures = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("status") == "FAILED" }
      prefs(context).edit().putLong("last_cycle", System.currentTimeMillis())
        .putString("engine_error", failures.joinToString("; ") { it.optString("pair") + ": " + it.optString("error") }.take(600)).apply()
      handoff(context, false)
      return failures.isEmpty()
    } catch (error: Exception) {
      prefs(context).edit().putString("engine_error", (error.message ?: "Telefon motoru hatası").take(600)).apply()
      // A market outage must not prevent backing up already collected research.
      try { handoff(context, false) } catch (_: Exception) { }
      return false
    } finally { running = false; engineLock.unlock() }
  }
  private fun handoff(context: Context, force: Boolean) {
    val settings = prefs(context)
    val target = settings.getString("drive_uri", null) ?: return
    if (!connected(context)) return
    if (!force && System.currentTimeMillis() - settings.getLong("backup_handoff", 0) < 15 * 60_000L) return
    try {
      val file = File(module(context).callAttr("backup", root(context).path, pairs(context)).toString())
      val stream = context.contentResolver.openOutputStream(Uri.parse(target), "wt")
        ?: throw IllegalStateException("Seçilen Drive dosyası yazılamıyor.")
      stream.use { output -> file.inputStream().use { it.copyTo(output) }; output.flush() }
      settings.edit().putLong("backup_handoff", System.currentTimeMillis())
        .putLong("backup_bytes", file.length()).putString("backup_error", "").apply()
    } catch (_: Exception) {
      settings.edit().putString("backup_error", "Drive sağlayıcısına yazılamadı. Drive hesabını, interneti ve dosya erişimini kontrol et; yedek telefonda korunuyor.").apply()
    }
  }
  fun backupNow(context: Context): String {
    if (!engineLock.tryLock(1, TimeUnit.SECONDS)) throw IllegalStateException("Araştırma sürüyor; yedekleme için işin bitmesini bekle.")
    try {
      if (prefs(context).getString("drive_uri", null) == null) throw IllegalStateException("Önce Drive yedek dosyasını seç.")
      if (!connected(context)) throw IllegalStateException("İnternet yok; yedek telefonda korunuyor.")
      handoff(context, true)
      val error = prefs(context).getString("backup_error", "")!!
      if (error.isNotEmpty()) throw IllegalStateException(error)
      return status(context)
    } finally { engineLock.unlock() }
  }
  fun status(context: Context): String {
    val settings = prefs(context)
    return JSONObject().put("available", true).put("enabled", settings.getBoolean("enabled", false))
      .put("running", running).put("foreground", foreground).put("charging", charging(context))
      .put("scoreRunning", scoreRunning || scoreQueued).put("scoreNextAt", scoreNextAt)
      .put("lastScoreCycle", settings.getLong("last_score_cycle", 0)).put("scoreError", settings.getString("score_error", ""))
      .put("periodicScheduled", periodicScheduled(context))
      .put("pairs", org.json.JSONArray(pairs(context))).put("lastCycle", settings.getLong("last_cycle", 0))
      .put("engineError", settings.getString("engine_error", ""))
      .put("serviceNote", settings.getString("service_note", ""))
      .put("driveSelected", settings.contains("drive_uri")).put("driveName", settings.getString("drive_name", ""))
      .put("backupHandoff", settings.getLong("backup_handoff", 0)).put("backupBytes", settings.getLong("backup_bytes", 0))
      .put("backupError", settings.getString("backup_error", ""))
      .put("coverage", JSONObject(module(context).callAttr("status", root(context).path, pairs(context)).toString())).toString()
  }
  private fun periodicScheduled(context: Context): Boolean {
    if (!prefs(context).getBoolean("enabled", false)) return false
    return try {
      WorkManager.getInstance(context).getWorkInfosForUniqueWork("radar-phone-periodic").get(2, TimeUnit.SECONDS).any { !it.state.isFinished }
    } catch (_: Exception) { false }
  }
  fun schedule(context: Context) {
    val work = PeriodicWorkRequestBuilder<RadarWorker>(15, TimeUnit.MINUTES)
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("radar-phone-periodic", ExistingPeriodicWorkPolicy.KEEP, work)
  }
  fun enqueue(context: Context) {
    if (!prefs(context).getBoolean("enabled", false)) return
    val work = OneTimeWorkRequestBuilder<RadarWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniqueWork("radar-phone-now", ExistingWorkPolicy.KEEP, work)
  }
}

class RadarWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
  override fun doWork(): Result {
    if (!PhoneRuntime.prefs(applicationContext).getBoolean("enabled", false)) return Result.success()
    return if (PhoneRuntime.cycle(applicationContext)) Result.success() else Result.retry()
  }
}
