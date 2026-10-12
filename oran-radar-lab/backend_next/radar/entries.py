"""Closed-bar limit-entry research; fees, missed trades and ordering uncertainty.

Fixed policies are selected on a chronological validation partition. The final
partition evaluates the frozen winner against buying the next open. Outcomes
are non-overlapping 54-hour windows on a UTC 55-hour grid. Intrabar fill/target
order is bounded, never invented. Reports are descriptive paper evidence.
"""

import copy
import hashlib
import json
import math

import numpy as np

from .config import HOUR
from .science import norm
from .statistics import alpha_budget, effective_n, precision_n, wilson
from .stops import advance, new_trial, paired_summary

POLICIES = (
    "pct:0.005",
    "pct:0.01",
    "pct:0.02",
    "pct:0.03",
    "atr:0.5",
    "atr:1",
    "support:24",
    "vwap:24",
)
PROTOCOL = "closed-limit-entry-v2"
WAIT, HOLD, STRIDE = 6, 48, 55


def design(cfg):
    values = {
        k: getattr(cfg, k)
        for k in (
            "fee_bps",
            "stop_slippage_bps",
            "stop_max_fraction",
            "stop_target_atr",
            "min_move",
            "entry_alpha",
            "entry_min_gain_bps",
            "power",
            "ci_half_width",
        )
    }
    values["configured_pair_count"] = max(1, len(cfg.pairs))
    values.update(
        protocol=PROTOCOL, policies=POLICIES, wait=WAIT, hold=HOLD, stride=STRIDE
    )
    return hashlib.sha256(json.dumps(values, sort_keys=True).encode()).hexdigest()[:16]


def context(bars, i):
    recent = bars[max(0, i - 23) : i + 1]
    anchor = bars[i]["close"]
    tr = [
        max(
            bars[j]["high"] - bars[j]["low"],
            abs(bars[j]["high"] - bars[j - 1]["close"]),
            abs(bars[j]["low"] - bars[j - 1]["close"]),
        )
        for j in range(i - 13, i + 1)
    ]
    weights = [max(0, b.get("quote_volume", 0)) for b in recent]
    typical = [(b["high"] + b["low"] + b["close"]) / 3 for b in recent]
    # quote-volume weighted typical price (not exchange trade-level VWAP).
    weighted = (
        float(np.average(typical, weights=weights))
        if sum(weights)
        else float(np.mean(typical))
    )
    return {
        "anchor": anchor,
        "atr_fraction": float(np.mean(tr)) / anchor,
        "support": min(b["low"] for b in recent),
        "weighted_price": weighted,
    }


def entry_level(policy, ctx, cfg):
    kind, number = policy.split(":")
    floor = max(0.0001, (cfg.fee_bps + 2 * cfg.stop_slippage_bps) / 10000)
    if kind == "pct":
        discount = float(number)
    elif kind == "atr":
        discount = float(number) * ctx["atr_fraction"]
    else:
        level = ctx["support"] if kind == "support" else ctx["weighted_price"]
        discount = 1 - level / ctx["anchor"]
    discount = min(cfg.stop_max_fraction, max(floor, discount))
    return ctx["anchor"] * (1 - discount)


def risk_levels(ctx, cfg):
    floor = max(0.0001, (cfg.fee_bps + 2 * cfg.stop_slippage_bps) / 10000)
    return min(cfg.stop_max_fraction, max(floor, 1.5 * ctx["atr_fraction"])), min(
        0.5, max(cfg.min_move, 2 * floor, cfg.stop_target_atr * ctx["atr_fraction"])
    )


def simulate(window, limit, stop, target, cfg, market=False):
    """Per-decision returns: no fill is zero, not excluded from the mean."""
    fill = (
        0
        if market
        else next((i for i, b in enumerate(window[:WAIT]) if b["low"] <= limit), None)
    )
    if fill is None:
        return {
            "filled": False,
            "outcome": "UNFILLED",
            "net_lower": 0.0,
            "net_upper": 0.0,
            "fill_delay": None,
        }
    first = window[fill]
    entry = first["open"] if market or first["open"] <= limit else limit
    entry *= 1 + cfg.stop_slippage_bps / 10000 if market else 1
    paths = [
        new_trial({"id": "atr:1.5", "distance": stop}, entry, target) for _ in range(2)
    ]
    deadline = window[fill + HOLD - 1]["time"]
    for j, bar in enumerate(window[fill : fill + HOLD]):
        for path_no, trial in enumerate(paths):
            tick = bar
            if not market and j == 0 and first["open"] > limit:
                tick = {**bar, "open": entry}
                if path_no == 0:
                    tick["high"] = max(entry, bar["close"])
            advance(trial, tick, entry, deadline, cfg.fee_bps, cfg.stop_slippage_bps)
    lower, upper = (
        min(t["net_lower"] for t in paths),
        max(t["net_upper"] for t in paths),
    )
    return {
        "filled": True,
        "outcome": paths[0]["outcome"] if lower == upper else "AMBIGUOUS",
        "net_lower": lower,
        "net_upper": upper,
        "fill_delay": fill + 1,
    }


def samples(bars, cfg):
    rows = []
    for i in range(48, len(bars) - (WAIT + HOLD)):
        if (bars[i]["time"] // HOUR) % STRIDE:
            continue
        block = bars[i - 48 : i + WAIT + HOLD + 1]
        if any(b["time"] - a["time"] != HOUR for a, b in zip(block, block[1:])):
            continue
        ctx = context(bars, i)
        stop, target = risk_levels(ctx, cfg)
        window = bars[i + 1 : i + 1 + WAIT + HOLD]
        rows.append(
            {
                "time": bars[i]["time"],
                "end": window[-1]["time"] + HOUR,
                "trials": {
                    p: simulate(window, entry_level(p, ctx, cfg), stop, target, cfg)
                    for p in POLICIES
                },
                "baseline": simulate(window, None, stop, target, cfg, market=True),
            }
        )
    return rows


def metrics(rows, policy, alpha):
    trials = [r["trials"][policy] for r in rows]
    n = len(trials)
    net = paired_summary([t["net_lower"] for t in trials], alpha)
    gain = paired_summary(
        [r["trials"][policy]["net_lower"] - r["baseline"]["net_upper"] for r in rows],
        alpha,
    )
    fills = sum(t["filled"] for t in trials)
    fill_neff = effective_n([int(t["filled"]) for t in trials])
    return {
        "n": n,
        "effective_n": gain["effective_n"],
        "filled": fills,
        "fill_effective_n": fill_neff,
        "fill_rate": fills / n if n else None,
        "fill_interval": wilson(fills / n * fill_neff, fill_neff, alpha)
        if n
        else [None, None],
        "targets": sum(t["outcome"] == "TARGET_HIT" for t in trials),
        "stops": sum(t["outcome"] == "STOPPED" for t in trials),
        "ambiguous": sum(t["outcome"] == "AMBIGUOUS" for t in trials),
        "net": net,
        "gain": gain,
    }


def research(bars, cfg, pair, round_no, now):
    rows = samples(bars, cfg)
    first, second = int(len(rows) * 0.6), int(len(rows) * 0.8)
    train, validation, test = rows[:first], rows[first:second], rows[second:]
    # Adjacent windows cannot overlap; do not let train/validation outcomes cross boundaries.
    if validation:
        train = [r for r in train if r["end"] <= validation[0]["time"]]
    if test:
        validation = [r for r in validation if r["end"] <= test[0]["time"]]
    family_alpha = alpha_budget(round_no, cfg.entry_alpha) / max(1, len(cfg.pairs))
    comparison_alpha = family_alpha / (2 * len(POLICIES))
    candidates = [
        {
            "policy_id": p,
            "training": metrics(train, p, comparison_alpha),
            "validation": metrics(validation, p, comparison_alpha),
            "test": metrics(test, p, comparison_alpha),
        }
        for p in POLICIES
    ]

    def objective(row):
        stats = row["validation"]["net"]
        return (
            stats["interval"][0]
            if stats["interval"][0] is not None
            else stats["mean"]
            if stats["mean"] is not None
            else -math.inf
        )

    chosen = (
        max(candidates, key=objective)
        if validation
        else next(c for c in candidates if c["policy_id"] == "atr:1")
    )
    result = chosen["test"]
    delta = cfg.entry_min_gain_bps / 10000
    gains = [
        r["trials"][chosen["policy_id"]]["net_lower"] - r["baseline"]["net_upper"]
        for r in test
    ]
    sd = float(np.std(gains, ddof=1)) if len(gains) > 1 else delta
    z = norm.ppf(1 - comparison_alpha / 2) + norm.ppf(cfg.power)
    required = max(
        precision_n(cfg.ci_half_width, comparison_alpha),
        math.ceil(z * z * max(sd, delta / 2) ** 2 / delta**2),
    )
    lo, net_lo = result["gain"]["interval"][0], result["net"]["interval"][0]
    powered = (
        result["effective_n"] >= required and result["net"]["effective_n"] >= required
    )
    status = (
        "HISTORICAL_SUPPORT"
        if powered
        and lo is not None
        and lo > delta
        and net_lo is not None
        and net_lo > 0
        else "NOT_SUPPORTED"
        if powered
        else "UNDERPOWERED"
    )
    return {
        "protocol": PROTOCOL,
        "design": design(cfg),
        "pair": pair,
        "round": round_no,
        "trained_at": now,
        "trained_bar": bars[-1]["time"] if bars else None,
        "status": status,
        "policy_id": chosen["policy_id"],
        "required_samples": required,
        "effective_samples": result["effective_n"],
        "training_samples": len(train),
        "validation_samples": len(validation),
        "evaluation_samples": len(test),
        "comparison_alpha": comparison_alpha,
        "minimum_gain_bps": cfg.entry_min_gain_bps,
        "power": cfg.power,
        "objective": "validation_lower_bound_net_per_decision",
        "test": result,
        "candidates": candidates,
        "split_bounds": {
            "train_end": train[-1]["end"] if train else None,
            "validation_start": validation[0]["time"] if validation else None,
            "validation_end": validation[-1]["end"] if validation else None,
            "test_start": test[0]["time"] if test else None,
        },
        "warning": "Sanal limit alışlar; dolum sırası belirsizliği kötümser hesaplandı. Gelecek için optimum veya kazanç garantisi değildir.",
    }


def update(state, bars, cfg, pair, now, allow_training=True, force=False):
    closed = [b for b in bars if b["time"] + HOUR <= now]
    previous = state.get("entry_research", {})
    due = (
        not previous
        or previous.get("design") != design(cfg)
        or (force or now - previous.get("trained_at", 0) >= cfg.retrain_ms)
        and previous.get("trained_bar") != (closed[-1]["time"] if closed else None)
    )
    if due and allow_training:
        state["entry_research"] = research(
            closed, cfg, pair, previous.get("round", 0) + 1, now
        )
    state["entry_trained_at"] = state.get("entry_research", {}).get("trained_at")
    return "WAITING_CHARGE" if due and not allow_training else "OK"


def compatible(report, cfg, now):
    return bool(
        report
        and report.get("protocol") == PROTOCOL
        and report.get("design") == design(cfg)
        and report.get("trained_at", now + 1) <= now
    )


def public_report(state):
    return copy.deepcopy(state.get("entry_research", {}))
