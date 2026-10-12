"""Original base Rally Score; research_score adds a separate, bounded overlay.

The six weights and transformations match backend/engine.py::score_market.
This descriptive 0..100 index is not a calibrated success probability.
"""

import math
from statistics import mean

WEIGHTS = {
    "trend": 0.25,
    "early": 0.17,
    "volume": 0.21,
    "compression": 0.14,
    "flow": 0.16,
    "breadth": 0.07,
}


def clamp(value):
    return max(0, min(1, value))


def score_market(hourly, minute, book, btc_change=0):
    if len(hourly) < 80 or len(minute) < 60:
        return None
    closes = [b["close"] for b in hourly]
    volumes = [b["quote_volume"] for b in minute]
    if not all(math.isfinite(v) and v > 0 for v in closes):
        raise ValueError("Invalid Rally price history")
    if not all(math.isfinite(v) and v >= 0 for v in volumes):
        raise ValueError("Invalid Rally volume history")
    price = closes[-1]
    trend = clamp(0.5 + (price / mean(closes[-24:]) - 1) * 22)
    early = clamp(0.7 - max(0, price / max(closes[-48:]) - 1) * 12)
    qvol = mean(volumes[-5:]) / max(1, mean(volumes[-60:-5]))
    volume = clamp((qvol - 0.6) / 2.2)
    ranges = [(b["high"] - b["low"]) / b["close"] for b in hourly]
    compression = clamp(1 - mean(ranges[-8:]) / max(1e-9, mean(ranges[-72:-8])))
    bid, ask = book["bids"], book["asks"]
    if not bid or not ask:
        raise ValueError("Order book is empty")
    levels = [(float(p), float(q)) for p, q in bid[:10] + ask[:10]]
    if any(
        not math.isfinite(p) or not math.isfinite(q) or p <= 0 or q < 0
        for p, q in levels
    ):
        raise ValueError("Invalid order book")
    spread = (float(ask[0][0]) - float(bid[0][0])) / price
    if spread < 0 or not math.isfinite(btc_change):
        raise ValueError("Invalid Rally market context")
    bsum = sum(float(p) * float(q) for p, q in bid[:10])
    asum = sum(float(p) * float(q) for p, q in ask[:10])
    imbalance = bsum / max(bsum + asum, 1)
    parts = {
        "trend": trend,
        "early": early,
        "volume": volume,
        "compression": compression,
        "flow": clamp(0.5 + (imbalance - 0.5) * 1.6),
        "breadth": clamp(0.5 + btc_change * 15),
    }
    score = round(100 * sum(parts[key] * weight for key, weight in WEIGHTS.items()))
    return {
        "score": score,
        "spread_pct": round(spread * 100, 4),
        "trend": round(trend, 3),
        "volume_ratio": round(qvol, 3),
        "compression": round(compression, 3),
        "book_imbalance": round(imbalance, 3),
        "btc_change": round(btc_change, 4),
        "price": price,
        "regime": "RISK_OFF"
        if btc_change < -0.02
        else "RISK_ON"
        if btc_change > 0.02
        else "NEUTRAL",
        "components": {key: round(value * 100, 2) for key, value in parts.items()},
        "weights": WEIGHTS,
        "method": "original-rally-v0.1",
        "probability": False,
    }
