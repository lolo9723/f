package com.oranradar.phone

import org.json.JSONArray
import org.json.JSONObject

/** Exchange observations, never fills or closed-candle research samples. */
class PriceBook {
  private val quotes = linkedMapOf<String, JSONObject>()
  var lastReceivedAt = 0L; private set
  private fun validSymbol(value: String) = value.matches(Regex("[A-Z0-9]{2,20}TRY"))
  private fun accept(symbol: String, price: Double, change: Double, volume: Double, observed: Long, event: Long, order: Long, source: String): Boolean {
    if (!validSymbol(symbol) || !price.isFinite() || price <= 0 || !change.isFinite() || !volume.isFinite() || volume < 0) return false
    val old = quotes[symbol]
    if (old != null && order <= old.optLong("order_at")) return false
    quotes[symbol] = JSONObject().put("price", price).put("change", change).put("quote_volume", volume)
      .put("observed_at", observed).put("event_at", event).put("order_at", order).put("source", source)
    lastReceivedAt = maxOf(lastReceivedAt, observed)
    return true
  }
  fun websocket(text: String, now: Long): Int {
    val root = org.json.JSONTokener(text).nextValue()
    val rows = if (root is JSONObject) root.optJSONArray("data") else root as? JSONArray
    if (rows == null) return 0
    var count = 0
    for (i in 0 until rows.length()) {
      val row = rows.optJSONObject(i) ?: continue
      val event = row.optLong("E", -1)
      val price = row.optDouble("c", Double.NaN); val open = row.optDouble("o", Double.NaN)
      if (event < now - 30_000 || event > now + 5_000 || !open.isFinite() || open <= 0) continue
      if (accept(row.optString("s").replace("_", ""), price, price/open-1, row.optDouble("q", Double.NaN), now, event, event, "BINANCE_TR_WS")) count++
    }
    return count
  }
  fun rest(text: String, requestedAt: Long, receivedAt: Long): Int {
    val root = org.json.JSONTokener(text).nextValue()
    val rows = if (root is JSONObject && root.optInt("code", 0) == 0) root.optJSONArray("data") else root as? JSONArray
    require(rows != null) { "Binance TR fiyat yanıtı doğrulanamadı." }
    var count = 0
    for (i in 0 until rows.length()) {
      val row = rows.optJSONObject(i) ?: continue
      val symbol = row.optString("symbol").replace("_", "")
      // A slow REST response cannot replace a later WebSocket observation.
      if ((quotes[symbol]?.optLong("observed_at") ?: 0) > requestedAt) continue
      if (accept(symbol, row.optDouble("lastPrice", Double.NaN), row.optDouble("priceChangePercent", Double.NaN)/100,
          row.optDouble("quoteVolume", Double.NaN), receivedAt, requestedAt, requestedAt, "BINANCE_TR_REST")) count++
    }
    return count
  }
  fun restore(value: JSONObject, now: Long) {
    val rows = value.optJSONObject("tickers") ?: return
    for (symbol in rows.keys()) {
      val row = rows.optJSONObject(symbol) ?: continue
      val observed = row.optLong("observed_at")
      if (observed <= 0 || observed > now) continue
      val source = row.optString("source")
      if (source !in listOf("BINANCE_TR_WS", "BINANCE_TR_REST")) continue
      accept(symbol, row.optDouble("price"), row.optDouble("change"), row.optDouble("quote_volume"),
        observed, row.optLong("event_at"), row.optLong("order_at", observed), source)
    }
  }
  fun snapshot() = JSONObject().also { out -> quotes.forEach { (symbol, row) -> out.put(symbol, JSONObject(row.toString())) } }
}
