"""Bounded, retryable public Binance TR acquisition, strict closed-bar validation."""

import json
import time
import urllib.error
import urllib.parse
import urllib.request
import math
from .config import HOUR


class Market:
    source = "Binance TR"

    def get(self, path, **params):
        url = "https://api.binance.me" + path + "?" + urllib.parse.urlencode(params)
        request = urllib.request.Request(
            url, headers={"User-Agent": "OranRadarLab/2.0"}
        )
        for attempt in range(3):
            try:
                with urllib.request.urlopen(request, timeout=18) as response:
                    data = json.load(response)
                if isinstance(data, dict):
                    if data.get("code", 0) != 0:
                        raise ValueError(f"Binance TR API code {data.get('code')}")
                    data = data.get("data", data)
                if not isinstance(data, list):
                    raise ValueError("Unexpected Binance TR payload")
                return data
            except urllib.error.HTTPError as exc:
                if exc.code not in (429, 500, 502, 503, 504) or attempt == 2:
                    raise
                retry = min(float(exc.headers.get("Retry-After", 2**attempt)), 5.0)
                time.sleep(retry)
            except (TimeoutError, urllib.error.URLError):
                if attempt == 2:
                    raise
                time.sleep(2**attempt)

    def candles(self, pair, start, end):
        return self.get(
            "/api/v1/klines",
            symbol=pair,
            interval="1h",
            startTime=start,
            endTime=end,
            limit=1000,
        )

    def trades(self, pair, cursor=None, start=None, end=None):
        params = {"symbol": pair, "limit": 1000}
        if cursor is not None:
            params["fromId"] = cursor + 1
        else:
            params.update(startTime=start, endTime=end)
        return self.get("/api/v3/aggTrades", **params)


def validate(raw, now, interval=HOUR):
    bars = []
    for row in raw:
        t, close_at = int(row[0]), int(row[6])
        if close_at >= now:
            continue
        if t % interval or close_at != t + interval - 1:
            raise ValueError("Unexpected hourly candle timestamp")
        o, h, lo, c, vol, quote, taker = map(
            float, (row[1], row[2], row[3], row[4], row[5], row[7], row[10])
        )
        if (
            not all(math.isfinite(v) for v in (o, h, lo, c, vol, quote, taker))
            or min(o, h, lo, c) <= 0
            or vol < 0
            or quote < 0
            or taker < 0
            or lo > min(o, c)
            or h < max(o, c)
            or lo > h
            or taker > quote + 1e-6
        ):
            raise ValueError("Invalid OHLCV data; results withheld")
        bars.append(
            {
                "time": t,
                "open": o,
                "high": h,
                "low": lo,
                "close": c,
                "volume": vol,
                "quote_volume": quote,
                "taker_buy_quote": taker,
            }
        )
    if any(bars[i]["time"] >= bars[i + 1]["time"] for i in range(len(bars) - 1)):
        raise ValueError("Duplicate or unsorted source candles")
    return bars


def contiguous(bars):
    return all(
        bars[i + 1]["time"] - bars[i]["time"] == HOUR for i in range(len(bars) - 1)
    )


def ingest(market, pair, state, now, cfg):
    bars = state.get("bars", [])
    start = (
        (now // HOUR - cfg.lookback_days * 24) * HOUR
        if not bars
        else bars[-1]["time"] + HOUR
    )
    latest = (now // HOUR - 1) * HOUR
    reached = False
    for _ in range(cfg.max_pages):
        raw = market.candles(pair, start, now - 1)
        page = validate(raw, now)
        if any(b["time"] < start for b in page):
            raise ValueError("Source ignored pagination start")
        if not page:
            reached = bool(bars and bars[-1]["time"] >= latest)
            break
        # New listings can legitimately begin after the requested lookback start.
        if bars and page[0]["time"] != start:
            raise ValueError(
                f"Candle gap at {start}; backfill must succeed before inference"
            )
        if not contiguous(page):
            raise ValueError("Candle gap inside source page")
        bars = (bars + page)[-min(cfg.max_bars, cfg.lookback_days * 24) :]
        start = bars[-1]["time"] + HOUR
        reached = bars[-1]["time"] >= latest
        if reached:
            break
    if not contiguous(bars):
        raise ValueError("Persisted candle gap")
    return bars, reached
