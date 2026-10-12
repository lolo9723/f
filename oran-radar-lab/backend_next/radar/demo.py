"""Explicitly synthetic data for offline demonstrations; never a live-market result."""

import argparse
import json
import time
import numpy as np
from .config import Config, HOUR
from .pipeline import run_pair
from .storage import LocalStore, clock_ms


class DemoMarket:
    source = "SYNTHETIC_DEMO"

    def __init__(self, days=730):
        now = clock_ms()
        count = days * 24
        start = (now // HOUR - count) * HOUR
        rng = np.random.default_rng(42)
        prices = 100 * np.exp(np.cumsum(rng.normal(0, 0.012, count)))
        self.bars = []
        for i, close in enumerate(prices):
            op = prices[i - 1] if i else close
            quote = float(rng.lognormal(13, 0.5))
            self.bars.append(
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

    def candles(self, pair, start, end):
        return [
            [
                b["time"],
                b["open"],
                b["high"],
                b["low"],
                b["close"],
                b["volume"],
                b["time"] + HOUR - 1,
                b["quote_volume"],
                50,
                0,
                b["taker_buy_quote"],
            ]
            for b in self.bars
            if start <= b["time"] <= end
        ][:1000]

    def trades(self, *args):
        # Demo never fabricates trade evidence for manual positions.
        raise ValueError("Demo market contains no actual trades")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="local-data/demo")
    parser.add_argument("--days", type=int, default=730)
    args = parser.parse_args()
    cfg = Config(pairs=("BTCTRY",), lookback_days=args.days)
    market, store = DemoMarket(args.days), LocalStore(args.root)
    started = time.monotonic()
    for step in range(10):
        result = run_pair(store, market, cfg, "BTCTRY", f"demo:{clock_ms()}:{step}")
        print(json.dumps(result), flush=True)
        if result.get("backfill_complete"):
            break
    store.heartbeat(clock_ms())
    report = store.read("BTCTRY", public=True)
    print(
        json.dumps(
            {
                "source": report["source"],
                "bars": report["bars"],
                "hypotheses": report["hypothesis_count"],
                "supported_in_top40": sum(
                    h["status"] == "SUPPORTED" for h in report["hypotheses"]
                ),
                "seconds": round(time.monotonic() - started, 2),
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
