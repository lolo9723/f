"""All predictors at t use closed observations <= t. No future-filled data."""

import numpy as np

WARMUP = 96
BASE_NAMES = (
    "return_1",
    "return_3",
    "return_6",
    "return_24",
    "trend_12",
    "trend_48",
    "volatility_24",
    "range_compression",
    "volume_ratio",
    "volume_z",
    "taker_share",
    "body",
    "upper_wick",
    "lower_wick",
    "range_position",
    "drawdown_48",
    "breakout_distance",
    "return_autocorr",
    "volume_price_corr",
    "return_skew",
    "rsi_14",
    "amihud_24",
)
NAMES = tuple(f"{name}@lag{lag}" for lag in (0, 1, 3, 6) for name in BASE_NAMES)


def corr(a, b):
    if np.std(a) < 1e-12 or np.std(b) < 1e-12:
        return 0.0
    return float(np.corrcoef(a, b)[0, 1])


def base_at(bars, i):
    w = bars[i - 72 : i + 1]
    c = np.array([b["close"] for b in w])
    v = np.array([b["quote_volume"] for b in w])
    r = np.diff(np.log(c))
    rng = np.array([(b["high"] - b["low"]) / b["close"] for b in w])
    bar = bars[i]
    span = max(bar["high"] - bar["low"], bar["close"] * 1e-12)
    up = np.mean(np.maximum(r[-14:], 0))
    down = np.mean(np.maximum(-r[-14:], 0))
    vol = np.std(r[-24:])
    standardized = (r[-24:] - np.mean(r[-24:])) / max(vol, 1e-12)
    return np.array(
        [
            *(float(c[-1] / c[-1 - n] - 1) for n in (1, 3, 6, 24)),
            c[-1] / np.mean(c[-12:]) - 1,
            c[-1] / np.mean(c[-48:]) - 1,
            vol,
            np.mean(rng[-6:]) / max(np.mean(rng[-48:-6]), 1e-12),
            np.mean(v[-3:]) / max(np.mean(v[-48:-3]), 1e-12),
            (v[-1] - np.mean(v[-48:-1])) / max(np.std(v[-48:-1]), 1e-12),
            bar["taker_buy_quote"] / max(bar["quote_volume"], 1e-12),
            (bar["close"] - bar["open"]) / span,
            (bar["high"] - max(bar["close"], bar["open"])) / span,
            (min(bar["close"], bar["open"]) - bar["low"]) / span,
            (bar["close"] - bar["low"]) / span,
            c[-1] / max(c[-48:]) - 1,
            c[-1] / max(c[-49:-1]) - 1,
            corr(r[-24:-1], r[-23:]),
            corr(r[-24:], np.diff(np.log1p(v))[-24:]),
            np.mean(standardized**3),
            100 * up / max(up + down, 1e-12),
            np.mean(np.abs(r[-24:]) / np.maximum(v[-24:], 1)),
        ],
        dtype=float,
    )


def matrix(bars):
    base = {i: base_at(bars, i) for i in range(72, len(bars))}
    indices = np.arange(WARMUP, len(bars))
    x = np.array(
        [np.concatenate([base[int(i) - lag] for lag in (0, 1, 3, 6)]) for i in indices]
    )
    return indices, np.nan_to_num(x, nan=0, posinf=0, neginf=0)


def dataset(bars, indices, x, horizon, min_move, fee_bps):
    # Align UTC horizon boundaries: app restarts do not shift independent anchors.
    chosen = [
        j
        for j, i in enumerate(indices)
        if i + horizon < len(bars)
        and (bars[int(i)]["time"] // 3_600_000) % horizon == 0
    ]
    rows, labels, times, ends, moves, thresholds = [], [], [], [], [], []
    for j in chosen:
        i = int(indices[j])
        # End-to-end direction. A later wick touch is a different outcome and never substituted.
        move = bars[i + horizon]["close"] / bars[i]["close"] - 1
        vol = x[j, NAMES.index("volatility_24@lag0")]
        threshold = max(min_move, vol * np.sqrt(horizon), fee_bps / 10000)
        rows.append(x[j])
        labels.append([int(move >= threshold), int(move <= -threshold)])
        times.append(bars[i]["time"])
        ends.append(bars[i + horizon]["time"])
        moves.append(move)
        thresholds.append(threshold)
    return {
        "x": np.array(rows),
        "y": np.array(labels),
        "time": np.array(times),
        "end": np.array(ends),
        "return": np.array(moves),
        "threshold": np.array(thresholds),
    }
