"""Native TRY quotes are separate from closed-candle/statistical evidence."""

import json
import math
import re

PROTOCOL = "native-binance-tr-prices-v1"


def live_snapshot(root, now):
    path = root / "PHONE-LIVE.json"
    try:
        if path.stat().st_size > 5_000_000:
            return {}
        data = json.loads(path.read_text())
        if data.get("protocol") != PROTOCOL or not isinstance(
            data.get("tickers"), dict
        ):
            return {}
        result = {**data, "tickers": {}}
        for pair, row in data["tickers"].items():
            if not re.fullmatch(r"[A-Z0-9]{2,20}TRY", pair) or not isinstance(
                row, dict
            ):
                continue
            values = [
                row.get(k) for k in ("price", "change", "quote_volume", "observed_at")
            ]
            if not all(
                isinstance(v, (int, float))
                and not isinstance(v, bool)
                and math.isfinite(v)
                for v in values
            ):
                continue
            if (
                row["price"] <= 0
                or row["quote_volume"] < 0
                or not 0 < row["observed_at"] <= now
                or row.get("source") not in ("BINANCE_TR_WS", "BINANCE_TR_REST")
            ):
                continue
            result["tickers"][pair] = row
        backoff = data.get("backoff_until", 0)
        result["backoff_until"] = (
            backoff
            if isinstance(backoff, (int, float))
            and math.isfinite(backoff)
            and now < backoff <= now + 86400_000
            else 0
        )
        return result
    except (OSError, ValueError, TypeError, AttributeError):
        return {}


def with_live(universe, snapshot):
    result = {**universe, "tickers": dict(universe.get("tickers", {}))}
    for pair, row in snapshot.get("tickers", {}).items():
        existing = result["tickers"].get(pair, {})
        if row["observed_at"] > existing.get("observed_at", 0):
            result["tickers"][pair] = row
    result["backoff_until"] = max(
        result.get("backoff_until", 0), snapshot.get("backoff_until", 0)
    )
    return result
