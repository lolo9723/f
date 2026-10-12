import numpy as np
from radar.config import HOUR


def make_bars(count=1800, seed=7, start=1704067200000):
    rng = np.random.default_rng(seed)
    moves = rng.normal(0, 0.012, count)
    prices = 100 * np.exp(np.cumsum(moves))
    bars = []
    for i, close in enumerate(prices):
        op = prices[i - 1] if i else close
        quote = float(rng.lognormal(13, 0.5))
        bars.append(
            {
                "time": start + i * HOUR,
                "open": float(op),
                "high": float(max(op, close) * 1.005),
                "low": float(min(op, close) * 0.995),
                "close": float(close),
                "volume": quote / close,
                "quote_volume": quote,
                "taker_buy_quote": quote * float(rng.uniform(0.2, 0.8)),
            }
        )
    return bars


def raw(bar):
    return [
        bar["time"],
        str(bar["open"]),
        str(bar["high"]),
        str(bar["low"]),
        str(bar["close"]),
        str(bar["volume"]),
        bar["time"] + HOUR - 1,
        str(bar["quote_volume"]),
        50,
        "0",
        str(bar["taker_buy_quote"]),
    ]


class FakeMarket:
    def __init__(self, bars):
        self.bars = bars
        self.calls = []
        self.trade_batches = []

    def candles(self, pair, start, end):
        self.calls.append((pair, start, end))
        return [raw(b) for b in self.bars if start <= b["time"] <= end][:1000]

    def trades(self, *args):
        return self.trade_batches.pop(0) if self.trade_batches else []
