import asyncio
import json
import time
from collections import defaultdict

from config import Settings
from engine import scenarios, score_market, state_for
from market import MAIN_REST, SYMBOLS_REST, fetch, market_stream, normalize_symbols, parse_candles
from storage import Store, now_ms


class Radar:
    def __init__(self, settings, store):
        self.cfg, self.store = settings, store
        self.symbols = {}
        self.tickers = {}
        self.selected = set()
        self.trade_queue = asyncio.Queue(maxsize=20000)
        self.started_at = now_ms()
        self.last_scan_at = None
        self.last_error = None
        self.scan_lock = asyncio.Lock()
        self.feed_blocked = set()
        self.charts = {}

    def on_tickers(self, batch):
        ts = now_ms()
        for t in batch:
            pair = t.get("s")
            if pair in self.symbols:
                self.tickers[pair] = {"price": float(t["c"]), "quote_volume": float(t["q"]),
                                      "open": float(t["o"]), "received_at": ts}

    def on_trade(self, data):
        try:
            self.trade_queue.put_nowait(data)
        except asyncio.QueueFull:
            self.feed_blocked.add(data["s"])
            self.last_error = "Trade queue overflow; gap must be reconciled before tracking."

    def selected_pairs(self):
        active = self.store.rows("SELECT pair FROM signals WHERE outcome IS NULL")
        paper = self.store.rows("SELECT pair FROM paper_positions WHERE state='TRACKING'")
        return self.selected | {s["pair"] for s in active + paper}

    async def process_trades(self):
        while True:
            event = await self.trade_queue.get()
            pair = event["s"]
            try:
                previous = self.store.rows("SELECT trade_id FROM feed_state WHERE pair=?", (pair,))
                if not previous:
                    paper = self.store.rows("SELECT MIN(created_at) AS start FROM paper_positions WHERE pair=? AND state='TRACKING'", (pair,))
                    if paper and paper[0]["start"] is not None:
                        # Seed at the first actual trade after the manual purchase, then the
                        # normal contiguous-ID backfill covers the subscription delay.
                        first = await fetch(MAIN_REST, "/api/v3/aggTrades", symbol=pair,
                                            startTime=paper[0]["start"],
                                            endTime=min(paper[0]["start"] + 3599_000, int(event["T"])), limit=1000)
                        if not first:
                            raise RuntimeError(f"Manual position first trade unavailable for {pair}")
                        trade = first[0]
                        if int(trade["a"]) > int(event["a"]):
                            raise RuntimeError(f"Invalid initial trade sequence for {pair}")
                        self.store.trade(pair, int(trade["a"]), int(trade["T"]), float(trade["p"]))
                        previous = self.store.rows("SELECT trade_id FROM feed_state WHERE pair=?", (pair,))
                expected = previous[0]["trade_id"] + 1 if previous else int(event["a"])
                incoming = int(event["a"])
                while expected < incoming:
                    batch = await fetch(MAIN_REST, "/api/v3/aggTrades",
                                        symbol=pair, fromId=expected, limit=1000)
                    if not batch or int(batch[0]["a"]) != expected:
                        raise RuntimeError(f"Unreconciled trade gap for {pair}: {expected} -> {incoming}")
                    advanced = False
                    for trade in batch:
                        tid = int(trade["a"])
                        if tid >= incoming:
                            break
                        if tid != expected:
                            raise RuntimeError(f"Non-contiguous trade id {pair}: {expected} -> {tid}")
                        self.store.trade(pair, tid, int(trade["T"]), float(trade["p"]))
                        expected += 1
                        advanced = True
                    if not advanced:
                        raise RuntimeError(f"No backfill progress {pair}")
                if expected <= incoming:
                    self.store.trade(pair, incoming, int(event["T"]), float(event["p"]))
                self.feed_blocked.discard(pair)
            except Exception as exc:
                self.feed_blocked.add(pair)
                self.last_error = str(exc)
                # Hold this event and later events until the missing public trades are available.
                await asyncio.sleep(5)
                await self.trade_queue.put(event)
            finally:
                self.trade_queue.task_done()

    async def scan_once(self):
        async with self.scan_lock:
            if not self.symbols:
                self.symbols = normalize_symbols(
                    await fetch(SYMBOLS_REST, "/open/v1/common/symbols"))
            candidates = sorted(
                ((pair, data) for pair, data in self.tickers.items()
                 if data["quote_volume"] >= self.cfg.min_quote_volume_try
                 and now_ms() - data["received_at"] < 120_000),
                key=lambda p: p[1]["quote_volume"], reverse=True)
            self.selected = {pair for pair, _ in candidates[:self.cfg.scan_pairs]}
            if not self.selected:
                self.last_error = "Waiting for fresh Binance TR mini tickers."
                return
            btc = self.tickers.get("BTCTRY")
            btc_change = (btc["price"] / btc["open"] - 1) if btc and btc["open"] else 0
            sem = asyncio.Semaphore(3)
            async def one(pair):
                async with sem:
                    try:
                        await self.scan_pair(pair, btc_change)
                    except Exception as exc:
                        self.last_error = f"{pair}: {exc}"
            await asyncio.gather(*(one(pair) for pair in self.selected))
            self.last_scan_at = now_ms()

    async def scan_pair(self, pair, btc_change):
        ts = now_ms()
        ticker = self.tickers[pair]
        if ts - ticker["received_at"] > 120_000:
            return
        # Fetch independent sources concurrently. Do not use unfinished bars.
        hr, minute, depth = await asyncio.gather(
            fetch(MAIN_REST, "/api/v1/klines", symbol=pair, interval="1h", limit=1000),
            fetch(MAIN_REST, "/api/v1/klines", symbol=pair, interval="1m", limit=120),
            fetch(MAIN_REST, "/api/v3/depth", symbol=pair, limit=20))
        hourly = parse_candles(hr, ts)
        minutes = parse_candles(minute, ts)
        self.charts[pair] = {"minute": minutes[-120:], "hour": hourly[-120:], "as_of": ts}
        features = score_market(hourly, minutes, depth, btc_change)
        if not features:
            return
        price = ticker["price"]
        options = scenarios(hourly, minutes, price, self.symbols[pair]["tick"],
                            features["spread_pct"], self.cfg.maker_fee_bps,
                            self.cfg.exit_fee_bps, self.cfg.slip_bps)
        best = options[0] if options else None
        state = state_for(features["score"], best, features["spread_pct"])
        regime = "RISK_OFF" if btc_change < -.025 else "RISK_ON" if btc_change > .025 else "NEUTRAL"
        features.update({"source": "Binance TR", "regime": regime, "snapshot_at": ts,
                         "estimate_status": "PROVISIONAL_UNCALIBRATED",
                         "minute_bars": len(minutes), "hour_bars": len(hourly)})
        self.store.opportunity(pair, self.symbols[pair], price, features["score"],
                               state, features["spread_pct"], best, features,
                               self.cfg.model_version, ts)
        active = self.store.rows("SELECT signal_id,initial_price FROM signals WHERE pair=? AND state='ENTRY' AND outcome IS NULL",
                                 (pair,))
        # Avoid cancelling while unprocessed trades may contain an earlier entry touch.
        if (active and self.trade_queue.empty() and pair not in self.feed_blocked and
            (age := self.store.feed_age(pair, ts)) is not None and age < 10_000 and
            (state == "WATCH" or regime == "RISK_OFF")):
            self.store.cancel_entry(pair, ts, "Rally conditions invalidated before paper entry")
        if state == "ENTRY" and best and not active and self.store.feed_age(pair, ts) is not None:
            if self.store.feed_age(pair, ts) < 120_000 and pair not in self.feed_blocked:
                self.store.create_signal(pair, self.symbols[pair], price, features["score"],
                    best, features, regime, self.cfg.model_version,
                    self.cfg.paper_notional_try,
                    self.cfg.maker_fee_bps + self.cfg.exit_fee_bps,
                    self.cfg.slip_bps, ts)

    async def scan_forever(self):
        while True:
            try:
                await self.scan_once()
            except Exception as exc:
                self.last_error = str(exc)
            self.store.expire(now_ms(), lambda pair, deadline:
                pair not in self.feed_blocked and
                (age := self.store.feed_age(pair, now_ms())) is not None and age < 90_000)
            await asyncio.sleep(self.cfg.scan_seconds)

    async def run(self):
        while True:
            try:
                self.symbols = normalize_symbols(
                    await fetch(SYMBOLS_REST, "/open/v1/common/symbols"))
                break
            except Exception as exc:
                self.last_error = str(exc)
                await asyncio.sleep(15)
        await asyncio.gather(
            market_stream(self.on_tickers, self.on_trade, self.selected_pairs),
            self.process_trades(), self.scan_forever())
