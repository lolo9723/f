package com.oranradar.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Independent of Python/GIL, score jobs and research locks. */
object PriceFeed {
  private const val WS = "wss://stream-cloud.binance.tr/ws/!miniTicker@arr"
  private const val REST = "https://api.binance.me/api/v1/ticker/24hr"
  private val loop = Executors.newSingleThreadScheduledExecutor()
  private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()
  private val book = PriceBook()
  @Volatile private var context: Context? = null
  @Volatile private var uiUntil = 0L
  @Volatile private var serviceActive = false
  @Volatile private var cached = "{\"protocol\":\"native-binance-tr-prices-v1\",\"state\":\"PAUSED\",\"tickers\":{}}"
  private var socket: WebSocket? = null
  private var call: Call? = null
  private var generation = 0L
  private var socketGeneration = 0L
  private var connected = false
  private var openedAt = 0L
  private var lastMessageAt = 0L
  private var lastWsQuoteAt = 0L
  private var nextConnectAt = 0L
  private var nextRestAt = 0L
  private var backoffUntil = 0L
  private var failures = 0
  private var lastRestAt = 0L
  private var error = ""
  private var dirty = true
  private var persistedAt = 0L
  private fun now() = System.currentTimeMillis()
  private fun wanted() = context?.let { PhoneRuntime.prefs(it).getBoolean("enabled", false) && (serviceActive || now() < uiUntil) } ?: false
  @Synchronized private fun ensure(ctx: Context) {
    if (context != null) return
    context = ctx.applicationContext
    loop.execute {
      try { val saved = JSONObject(cacheFile().readText()); book.restore(saved, now()); backoffUntil = saved.optLong("backoff_until") }
      catch (_: Exception) { }
      publish()
    }
    try {
      ctx.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { loop.execute { if (wanted()) { nextConnectAt = 0; nextRestAt = 0 } } }
        override fun onLost(network: Network) { loop.execute { if (wanted()) { disconnect(); error = "İnternet kesildi; son fiyatlar korunuyor."; publish() } } }
      })
    } catch (_: Exception) { }
    loop.scheduleWithFixedDelay({ try { maintain() } catch (e: Exception) { error = "Fiyat takibi: " + (e.message ?: "hata"); publish() } }, 0, 1, TimeUnit.SECONDS)
  }
  fun service(ctx: Context, active: Boolean) { ensure(ctx); serviceActive = active; loop.execute { maintain() } }
  fun prices(ctx: Context, visible: Boolean): String {
    ensure(ctx); uiUntil = if (visible) now() + 15_000 else 0
    loop.execute { maintain() }
    return cached
  }
  fun healthIssue(ctx: Context): String? {
    ensure(ctx)
    if (!PhoneRuntime.prefs(ctx).getBoolean("enabled", false)) return null
    if (!wanted()) return "PRICE_STREAM_PAUSED"
    val observed = try { JSONObject(cached).optLong("last_received_at") } catch (_: Exception) { 0 }
    return if (observed == 0L) "PRICE_FEED_PENDING" else if (observed > now() || now()-observed > 30_000) "PRICE_FEED_STALE" else null
  }
  fun recover(ctx: Context) {
    ensure(ctx); uiUntil = now()+15_000
    loop.execute { nextConnectAt = 0; nextRestAt = 0; maintain() }
  }
  fun pause(ctx: Context) { ensure(ctx); serviceActive = false; uiUntil = 0; loop.execute { disconnect(); publish() } }
  fun refreshOnce(ctx: Context) { ensure(ctx); loop.execute { if (PhoneRuntime.prefs(ctx).getBoolean("enabled", false)) fetchRest(force = true) } }
  private fun cacheFile() = File(PhoneRuntime.root(context!!), "PHONE-LIVE.json")
  private fun sharedBackoff(): Long {
    val existing = try { JSONObject(File(PhoneRuntime.root(context!!), "PHONE-MARKETS.json").readText()).optLong("backoff_until") } catch (_: Exception) { 0 }
    return maxOf(existing, backoffUntil)
  }
  private fun maintain() {
    if (!wanted()) { if (socket != null) disconnect(); publish(); return }
    if (!PhoneRuntime.connected(context!!)) { disconnect(); error = "İnternet yok; yeniden bağlantı bekleniyor."; publish(); return }
    val time = now()
    if (socket != null && time - maxOf(openedAt, lastWsQuoteAt) > 30_000) { disconnect(); error = "Fiyat akışı gecikti; bağlantı yenileniyor."; nextConnectAt = time + 1000 }
    if (socket == null && time >= nextConnectAt && time >= sharedBackoff()) connect()
    if (time >= nextRestAt) fetchRest()
    publish()
  }
  private fun connect() {
    val token = ++socketGeneration
    openedAt = now(); connected = false
    socket = client.newWebSocket(Request.Builder().url(WS).build(), object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) { loop.execute { if (token == socketGeneration && wanted()) { connected = true; publish() } } }
      override fun onMessage(webSocket: WebSocket, text: String) {
        val receivedAt = now()
        loop.execute {
          if (token != socketGeneration || !wanted()) return@execute
          lastMessageAt = receivedAt
          try {
            if (text.length <= 4_000_000 && book.websocket(text, receivedAt) > 0) { lastWsQuoteAt = receivedAt; failures = 0; error = ""; dirty = true }
          } catch (_: Exception) { /* Invalid/control frames never fabricate prices. */ }
          publish()
        }
      }
      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { loop.execute { failed(token, "Canlı fiyat bağlantısı kesildi.", response) } }
      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { loop.execute { failed(token, "Canlı fiyat bağlantısı kapandı.", null) } }
      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
    })
  }
  private fun limited(response: Response) {
    if (response.code !in listOf(418, 429)) return
    val raw = response.header("Retry-After")
    val date = try { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US).parse(raw ?: "")?.time } catch (_: Exception) { null }
    val seconds = raw?.toLongOrNull()
    backoffUntil = maxOf(backoffUntil, if (seconds != null) now() + seconds.coerceIn(1, 86400)*1000 else date ?: now()+60_000)
    nextRestAt = maxOf(nextRestAt, backoffUntil); nextConnectAt = maxOf(nextConnectAt, backoffUntil); dirty = true; persistedAt = 0
  }
  private fun failed(token: Long, message: String, response: Response?) {
    if (token != socketGeneration) return
    if (response != null) limited(response)
    socketGeneration++; socket = null; connected = false
    failures = minOf(6, failures+1); nextConnectAt = maxOf(backoffUntil, now()+minOf(60_000L, 1000L*(1L shl failures)))
    error = message; dirty = true; publish()
  }
  private fun disconnect() {
    socketGeneration++; socket?.cancel(); socket = null; connected = false
    generation++; call?.cancel(); call = null; dirty = true
  }
  private fun fetchRest(force: Boolean = false) {
    val time = now()
    if (call != null || time < sharedBackoff() || (!force && !wanted()) || !PhoneRuntime.connected(context!!)) return
    nextRestAt = time + if (connected && time-lastWsQuoteAt < 15_000) 30_000 else 10_000
    val token = generation
    val request = client.newCall(Request.Builder().url(REST).build()); call = request
    request.enqueue(object : Callback {
      override fun onFailure(call: Call, e: java.io.IOException) { loop.execute { if (token == generation && this@PriceFeed.call === call) { this@PriceFeed.call = null; error = "REST fiyatları alınamadı; son veriler korunuyor."; publish() } } }
      override fun onResponse(call: Call, response: Response) {
        try { response.use {
          val receivedAt = now(); val text = response.body?.string() ?: ""
          loop.execute {
            if (token != generation || this@PriceFeed.call !== call) return@execute
            this@PriceFeed.call = null
            if (!response.isSuccessful) { limited(response); error = "Binance TR fiyat isteği HTTP " + response.code; dirty = true; publish(); return@execute }
            try { if (book.rest(text, time, receivedAt) > 0) { lastRestAt = receivedAt; error = ""; dirty = true } }
            catch (_: Exception) { error = "REST fiyat yanıtı doğrulanamadı; son veriler korunuyor." }
            publish()
          }
        } } catch (e: java.io.IOException) { onFailure(call, e) }
      }
    })
  }
  private fun publish() {
    val time = now()
    val state = if (!wanted()) "PAUSED" else if (connected && time-lastWsQuoteAt <= 15_000) "LIVE" else if (time-lastRestAt <= 30_000) "REST" else if (book.lastReceivedAt > 0) "STALE" else "CONNECTING"
    val data = JSONObject().put("protocol", "native-binance-tr-prices-v1").put("state", state).put("enabled", wanted())
      .put("ws_connected", connected).put("last_message_at", lastMessageAt).put("last_ws_quote_at", lastWsQuoteAt).put("last_received_at", book.lastReceivedAt)
      .put("last_rest_at", lastRestAt).put("next_connect_at", nextConnectAt).put("backoff_until", sharedBackoff())
      .put("error", error).put("as_of", time).put("tickers", book.snapshot())
    cached = data.toString()
    if (dirty && time-persistedAt >= 5000) {
      // Linux rename replaces a complete file atomically on every supported API,
      // including Android 7; Python readers never see a half-written snapshot.
      try {
        val temporary = File(cacheFile().parentFile, "PHONE-LIVE.next")
        temporary.writeText(cached, Charsets.UTF_8)
        check(temporary.renameTo(cacheFile()))
        dirty = false; persistedAt = time
      } catch (_: Exception) { }
    }
  }
}
