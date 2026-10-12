"""Binance TR public market adapter. No account keys or order routes."""
import asyncio
import json
import urllib.parse
import urllib.request
from decimal import Decimal, ROUND_DOWN, ROUND_UP

import websockets

MAIN_REST = "https://api.binance.me"
SYMBOLS_REST = "https://www.binance.tr"
MAIN_WS = "wss://stream-cloud.binance.tr"
STABLE_ASSETS = {"USDT", "USDC", "FDUSD", "TUSD", "DAI", "EUR"}


def get_json(base, path, **params):
    query = "?" + urllib.parse.urlencode(params) if params else ""
    request = urllib.request.Request(base + path + query, headers={"User-Agent": "OranRadarPaper/0.1"})
    with urllib.request.urlopen(request, timeout=18) as response:
        result = json.load(response)
    if isinstance(result, dict) and result.get("code", 0) != 0:
        raise RuntimeError(f"Binance TR public API: {result.get('code')} {result.get('msg')}")
    return result.get("data", result) if isinstance(result, dict) else result


async def fetch(base, path, **params):
    return await asyncio.to_thread(get_json, base, path, **params)


def normalize_symbols(payload):
    symbols = {}
    for item in payload["list"]:
        if (item.get("quoteAsset") != "TRY" or item.get("type") != 1 or
            item.get("spotTradingEnable") != 1 or item.get("baseAsset") in STABLE_ASSETS):
            continue
        tick = next((f.get("tickSize") for f in item.get("filters", []) if f.get("filterType") == "PRICE_FILTER"), None)
        if not tick or Decimal(str(tick)) <= 0:
            continue
        pair = item["symbol"].replace("_", "")
        symbols[pair] = {"display": item["symbol"].replace("_", "/"), "tick": str(tick),
                         "coin": item["baseAsset"]}
    return symbols


def align_price(value, tick, direction="down"):
    unit = Decimal(str(tick))
    rounding = ROUND_DOWN if direction == "down" else ROUND_UP
    return float((Decimal(str(value)) / unit).to_integral_value(rounding=rounding) * unit)


def parse_candles(raw, now_ms):
    # Ignore the still-open bar: feature snapshots must only see closed candles.
    return [
        {"time": int(k[0]), "open": float(k[1]), "high": float(k[2]),
         "low": float(k[3]), "close": float(k[4]), "volume": float(k[5]),
         "quote_volume": float(k[7]), "taker_buy_quote": float(k[10])}
        for k in raw if int(k[6]) < now_ms
    ]


async def market_stream(on_tickers, on_trade, selected_pairs):
    """Reconnecting connection with dynamic subscriptions; mini tickers are never fill evidence."""
    delay = 2
    while True:
        try:
            async with websockets.connect(MAIN_WS + "/ws/!miniTicker@arr",
                                          ping_interval=150, ping_timeout=30,
                                          max_size=4_000_000) as ws:
                subscribed = set()
                delay = 2
                async def refresh():
                    nonlocal subscribed
                    wanted = set(selected_pairs())
                    add, remove = wanted - subscribed, subscribed - wanted
                    if add:
                        await ws.send(json.dumps({"method": "SUBSCRIBE",
                            "params": [s.lower() + "@aggTrade" for s in sorted(add)], "id": 1}))
                    if remove:
                        await ws.send(json.dumps({"method": "UNSUBSCRIBE",
                            "params": [s.lower() + "@aggTrade" for s in sorted(remove)], "id": 2}))
                    subscribed = wanted
                while True:
                    await refresh()
                    try:
                        frame = json.loads(await asyncio.wait_for(ws.recv(), timeout=15))
                    except asyncio.TimeoutError:
                        continue
                    data = frame.get("data", frame) if isinstance(frame, dict) else frame
                    if isinstance(data, list):
                        on_tickers(data)
                    elif isinstance(data, dict) and data.get("e") == "aggTrade":
                        on_trade(data)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            print("market stream disconnected:", repr(exc), flush=True)
            await asyncio.sleep(delay)
            delay = min(delay * 2, 60)
