package com.oranradar.phone

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RadarService : Service() {
  private val executor = Executors.newSingleThreadScheduledExecutor()
  private val scoreTimer = Executors.newSingleThreadScheduledExecutor()
  override fun onCreate() {
    super.onCreate()
    val manager = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("radar-phone", "Telefonda araştırma", NotificationManager.IMPORTANCE_LOW))
    val launch = packageManager.getLaunchIntentForPackage(packageName)!!
    val open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val pause = PendingIntent.getService(this, 1, Intent(this, RadarService::class.java).setAction("PAUSE"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "radar-phone") else Notification.Builder(this)
    val notification = builder.setSmallIcon(android.R.drawable.ic_menu_info_details)
      .setContentTitle("Oran Radar · Telefonda araştırma")
      .setContentText("Veriler telefonda saklanır. Ayrıntılar için dokun.").setOngoing(true)
      .setContentIntent(open).addAction(Notification.Action.Builder(null, "Duraklat", pause).build()).build()
    try { startForeground(703, notification); PhoneRuntime.foreground = true }
    catch (_: Exception) {
      PhoneRuntime.prefs(this).edit().putString("service_note", "Hızlı servis başlatılamadı; periyodik çalışma etkin. Uygulamadan tekrar başlatabilirsin.").apply()
      stopSelf()
      return
    }
    PriceFeed.service(applicationContext, true)
    executor.scheduleWithFixedDelay({ PhoneRuntime.cycle(applicationContext) }, 0, 5, TimeUnit.MINUTES)
    scoreTimer.scheduleWithFixedDelay({ PhoneRuntime.refreshScores(applicationContext) }, 0, 30, TimeUnit.SECONDS)
  }
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == "PAUSE" || !PhoneRuntime.prefs(this).getBoolean("enabled", false)) {
      PhoneRuntime.prefs(this).edit().putBoolean("enabled", false).apply()
      androidx.work.WorkManager.getInstance(this).cancelUniqueWork("radar-phone-periodic")
      androidx.work.WorkManager.getInstance(this).cancelUniqueWork("radar-phone-now")
      PriceFeed.pause(applicationContext)
      stopSelf()
      return START_NOT_STICKY
    }
    return START_STICKY
  }
  override fun onTimeout(startId: Int, fgsType: Int) {
    PhoneRuntime.prefs(this).edit().putString("service_note", "Android hızlı servis süre sınırına ulaştı. Periyodik arka plan çalışması sürer; uygulamadan hızlı servisi tekrar başlatabilirsin.").apply()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }
  override fun onDestroy() {
    executor.shutdownNow()
    scoreTimer.shutdownNow()
    PhoneRuntime.foreground = false
    PriceFeed.service(applicationContext, false)
    super.onDestroy()
  }
  override fun onBind(intent: Intent?): IBinder? = null
}
