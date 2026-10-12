package com.oranradar.phone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PriceBookTest {
  private val now = 1_700_000_000_000L
  private fun frame(price: String = "100", event: Long = now, symbol: String = "BTCTRY") = "[{\"s\":\"$symbol\",\"E\":$event,\"c\":\"$price\",\"o\":\"90\",\"q\":\"1000\"}]"
  private fun rest(price: String = "90") = "[{\"symbol\":\"BTCTRY\",\"lastPrice\":\"$price\",\"priceChangePercent\":\"5\",\"quoteVolume\":\"2000\"}]"
  @Test fun updatesSamePriceTimestampAndPreservesAllTryCoins() {
    val book = PriceBook()
    assertEquals(1, book.websocket(frame(), now))
    assertEquals(1, book.websocket(frame(event=now+1000), now+1000))
    assertEquals(now+1000, book.snapshot().getJSONObject("BTCTRY").getLong("observed_at"))
    for (i in 0..199) assertEquals(1, book.websocket(frame(symbol="COIN${i}TRY"), now+1000))
    assertEquals(201, book.snapshot().length())
  }
  @Test fun rejectsInvalidVenueFutureOldAndNonpositiveData() {
    val book = PriceBook()
    for (f in listOf(frame(symbol="BTCUSDT"), frame(price="0"), frame(price="NaN"), frame(event=now+6000), frame(event=now-31_000))) assertEquals(0, book.websocket(f, now))
    assertEquals(0, book.snapshot().length())
    assertEquals(0, book.websocket("{\"result\":null,\"id\":1}", now))
  }
  @Test fun oldFrameAndDelayedRestCannotRegressNewerWebsocket() {
    val book = PriceBook()
    book.websocket(frame(price="102",event=now+1000), now+1000)
    assertEquals(0, book.websocket(frame(price="99"), now+2000))
    assertEquals(0, book.websocket(frame(price="102",event=now+1000),now+2000))
    assertEquals(0, book.rest(rest(), now, now+3000))
    assertEquals(102.0, book.snapshot().getJSONObject("BTCTRY").getDouble("price"), 0.0)
    assertEquals(1, book.rest(rest("103"),now+4000,now+4500))
    assertEquals(103.0,book.snapshot().getJSONObject("BTCTRY").getDouble("price"),0.0)
  }
  @Test fun wrappedPayloadAndRestartKeepOriginalAgeAndSource() {
    val book = PriceBook()
    book.websocket("{\"stream\":\"!miniTicker@arr\",\"data\":"+frame()+"}",now)
    val old = JSONObject().put("tickers", book.snapshot())
    val recovered = PriceBook(); recovered.restore(old,now+60_000)
    assertEquals(now, recovered.snapshot().getJSONObject("BTCTRY").getLong("observed_at"))
    assertEquals("BINANCE_TR_WS",recovered.snapshot().getJSONObject("BTCTRY").getString("source"))
    recovered.snapshot().getJSONObject("BTCTRY").put("price",1)
    assertEquals(100.0,recovered.snapshot().getJSONObject("BTCTRY").getDouble("price"),0.0)
  }
  @Test fun restErrorsDoNotReplaceQuotes() {
    val book = PriceBook(); book.websocket(frame(),now)
    try { book.rest("{\"code\":-1,\"data\":[]}",now,now+1); fail("Error response accepted") } catch (_: IllegalArgumentException) { }
    assertEquals(100.0,book.snapshot().getJSONObject("BTCTRY").getDouble("price"),0.0)
    assertEquals(1,book.rest("{\"code\":0,\"data\":"+rest()+"}",now+2,now+3))
  }
}
