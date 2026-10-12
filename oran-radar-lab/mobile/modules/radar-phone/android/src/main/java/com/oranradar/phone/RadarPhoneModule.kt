package com.oranradar.phone

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.work.WorkManager
import expo.modules.kotlin.Promise
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONArray

class RadarPhoneModule : Module() {
  private val context get() = appContext.reactContext ?: throw IllegalStateException("Uygulama açık değil.")
  private var picker: Promise? = null
  override fun definition() = ModuleDefinition {
    Name("RadarPhone")
    AsyncFunction("prices") { visible: Boolean -> PriceFeed.prices(context, visible) }.runOnQueue(Queues.MAIN)
    AsyncFunction("status") { PhoneRuntime.status(context) }
    AsyncFunction("refreshScores") { pairs: List<String> -> PhoneRuntime.requestScores(context, pairs) }
    AsyncFunction("request") { method: String, path: String, query: String, body: String ->
      PhoneRuntime.request(context, method, path, query, body)
    }
    AsyncFunction("start") { pairs: List<String> ->
      require(pairs.distinct().size == pairs.size && pairs.all { it.matches(Regex("[A-Z0-9]{2,20}TRY")) }) { "Farklı TRY pariteleri gerekli." }
      PhoneRuntime.prefs(context).edit().putString("pairs", JSONArray(pairs).toString()).putBoolean("all_pairs", pairs.isEmpty()).putBoolean("enabled", true).putString("service_note", "").apply()
      PhoneRuntime.schedule(context)
      try {
        val service = Intent(context, RadarService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service) else context.startService(service)
      } catch (_: Exception) {
        PhoneRuntime.prefs(context).edit().putString("service_note", "Hızlı servis başlatılamadı; periyodik çalışma etkin.").apply()
      }
      PhoneRuntime.status(context)
    }
    AsyncFunction("stop") {
      PhoneRuntime.prefs(context).edit().putBoolean("enabled", false).apply()
      PriceFeed.pause(context)
      context.stopService(Intent(context, RadarService::class.java))
      WorkManager.getInstance(context).cancelUniqueWork("radar-phone-periodic")
      WorkManager.getInstance(context).cancelUniqueWork("radar-phone-now")
      PhoneRuntime.status(context)
    }
    AsyncFunction("backupNow") { PhoneRuntime.backupNow(context) }
    AsyncFunction("selectDrive") { promise: Promise ->
      val activity = appContext.currentActivity
      if (activity == null || picker != null) promise.reject("PICKER_UNAVAILABLE", "Dosya seçimi için uygulamayı öne getir.", null)
      else {
        picker = promise
        activity.runOnUiThread {
          try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
              .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "Oran-Radar-Phone.zip")
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            activity.startActivityForResult(intent, 704)
          } catch (error: Exception) {
            picker = null
            promise.reject("PICKER_FAILED", "Drive uygulamasını kur ve dosya seçimini tekrar dene.", error)
          }
        }
      }
    }
    OnActivityResult { _, result ->
      if (result.requestCode == 704) {
        val promise = picker
        picker = null
        if (result.resultCode != Activity.RESULT_OK || result.data?.data == null) promise?.resolve(null)
        else {
          try {
            val uri = result.data!!.data!!
            require(uri.scheme == "content" && uri.authority in listOf("com.google.android.apps.docs.storage", "com.google.android.apps.docs.storage.legacy")) { "Dosya seçicisinde soldaki menüden Google Drive hesabını seç; telefonun İndirilenler klasörünü seçme." }
            val flags = result.data!!.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            require(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) { "Drive yazma izni gerekli." }
            context.contentResolver.takePersistableUriPermission(uri, flags)
            var name = "Oran-Radar-Phone.zip"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
              if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
            }
            val old = PhoneRuntime.prefs(context).getString("drive_uri", null)
            PhoneRuntime.prefs(context).edit().putString("drive_uri", uri.toString()).putString("drive_name", name)
              .putLong("backup_handoff", 0).putLong("drive_selected_at", System.currentTimeMillis()).putString("backup_error", "").apply()
            if (old != null && old != uri.toString()) {
              try { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
            }
            PhoneRuntime.enqueue(context)
            promise?.resolve(PhoneRuntime.status(context))
          } catch (error: Exception) { promise?.reject("DRIVE_SELECTION_FAILED", error.message, error) }
        }
      }
    }
    OnDestroy {
      picker?.reject("PICKER_CLOSED", "Dosya seçimi kapanmış; tekrar dene.", null)
      picker = null
    }
  }
}
