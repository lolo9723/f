"""Causal long-only stop research and immutable, prospective OHLCV shadow trials.

These are paper simulations, not orders or verified exchange fills. Intrabar
ordering is withheld; paired comparisons use the pessimistic return bound.
"""

import copy
import hashlib
import json
import math

import numpy as np
from .science import norm, student_t

from .config import HOUR
from .research import split
from .statistics import alpha_budget, effective_n, precision_n, wilson

WARMUP = 48
REGIMES = ("quiet/normal", "quiet/high", "volatile/normal", "volatile/high")


def policies(cfg):
    return [
        {"id": f"pct:{p:g}", "kind": "percent", "value": p}
        for p in cfg.stop_percentages
    ] + [
        {"id": f"atr:{a:g}", "kind": "atr", "value": a}
        for a in cfg.stop_atr_multipliers
    ]


def design(cfg):
    value = {
        "policies": policies(cfg),
        "horizons": cfg.stop_horizons,
        "fee_bps": cfg.fee_bps,
        "slippage_bps": cfg.stop_slippage_bps,
        "max_fraction": cfg.stop_max_fraction,
        "target_atr": cfg.stop_target_atr,
        "min_move": cfg.min_move,
        "power": cfg.power,
        "alpha": cfg.stop_alpha,
        "minimum_gain_bps": cfg.stop_min_gain_bps,
        "ci_half_width": cfg.ci_half_width,
        "protocol": "long-stops-v1",
        "entry_delay_hours": 1,
    }
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()[:16]


def context_at(bars, i):
    assert i >= WARMUP
    true_ranges = [
        max(
            bars[j]["high"] - bars[j]["low"],
            abs(bars[j]["high"] - bars[j - 1]["close"]),
            abs(bars[j]["low"] - bars[j - 1]["close"]),
        )
        for j in range(i - 13, i + 1)
    ]
    atr = float(np.mean(true_ranges)) / bars[i]["close"]
    baseline = np.mean([b["quote_volume"] for b in bars[i - 24 : i]])
    volume = np.mean([b["quote_volume"] for b in bars[i - 2 : i + 1]])
    return {"atr_fraction": atr, "volume_ratio": float(volume / max(baseline, 1e-12))}


def regime(context, cutoffs):
    return (
        ("volatile" if context["atr_fraction"] > cutoffs["atr_fraction"] else "quiet")
        + "/"
        + ("high" if context["volume_ratio"] > cutoffs["volume_ratio"] else "normal")
    )


def levels(context, cfg):
    floor = max(0.0001, (cfg.fee_bps + 2 * cfg.stop_slippage_bps) / 10000)
    stops = [
        {
            **p,
            "distance": min(
                cfg.stop_max_fraction,
                max(
                    floor,
                    p["value"]
                    if p["kind"] == "percent"
                    else p["value"] * context["atr_fraction"],
                ),
            ),
        }
        for p in policies(cfg)
    ]
    target = min(
        0.5, max(cfg.min_move, floor, cfg.stop_target_atr * context["atr_fraction"])
    )
    return stops, target


def new_trial(stop, entry, target):
    return {
        "policy_id": stop["id"],
        "distance": stop["distance"],
        "stop_price": None
        if stop["id"] == "no-stop"
        else entry * (1 - stop["distance"]),
        "target_price": entry * (1 + target),
        "outcome": None,
        "exit_bar": None,
        "net_lower": None,
        "net_upper": None,
        "recovered_after_stop": False,
        "post_stop_mfe": None,
    }


def advance(trial, bar, entry, deadline, fee_bps, slippage_bps):
    target, stop = trial["target_price"], trial["stop_price"]
    if trial["outcome"] is not None:
        if trial["outcome"] == "STOPPED" and bar["time"] > trial["exit_bar"]:
            trial["recovered_after_stop"] |= bar["high"] >= target
            move = bar["high"] / entry - 1
            previous = trial["post_stop_mfe"]
            trial["post_stop_mfe"] = move if previous is None else max(previous, move)
        return
    op = bar["open"]
    if stop is not None and op <= stop:
        outcome, lower, upper = "STOPPED", op, op
    elif op >= target:
        # A limit target gets no invented favorable gap improvement.
        outcome, lower, upper = "TARGET_HIT", target, target
    elif stop is not None and bar["low"] <= stop and bar["high"] >= target:
        outcome, lower, upper = "AMBIGUOUS", stop, target
    elif stop is not None and bar["low"] <= stop:
        outcome, lower, upper = "STOPPED", stop, stop
    elif bar["high"] >= target:
        outcome, lower, upper = "TARGET_HIT", target, target
    elif bar["time"] == deadline:
        outcome, lower, upper = "TIMEOUT", bar["close"], bar["close"]
    else:
        return
    trial.update(
        outcome=outcome,
        exit_bar=bar["time"],
        net_lower=lower * (1 - slippage_bps / 10000) / entry - 1 - fee_bps / 10000,
        net_upper=upper * (1 - slippage_bps / 10000) / entry - 1 - fee_bps / 10000,
    )
    if outcome == "STOPPED" and op <= stop:
        # Opening price establishes stop-before-recovery order in a gap.
        trial["recovered_after_stop"] = bar["high"] >= target
        trial["post_stop_mfe"] = bar["high"] / entry - 1


def simulate(window, entry, stops, target, cfg):
    trials = [
        new_trial(p, entry, target) for p in [*stops, {"id": "no-stop", "distance": 0}]
    ]
    for bar in window:
        for trial in trials:
            advance(
                trial,
                bar,
                entry,
                window[-1]["time"],
                cfg.fee_bps,
                cfg.stop_slippage_bps,
            )
    return {r["policy_id"]: r for r in trials}


def required_samples(sd, cfg, alpha):
    delta = cfg.stop_min_gain_bps / 10000
    z = norm.ppf(1 - alpha / 2) + norm.ppf(cfg.power)
    return max(
        precision_n(cfg.ci_half_width),
        math.ceil(z * z * max(sd, delta / 2) ** 2 / delta**2),
    )


def paired_summary(gains, alpha=0.05):
    a = np.asarray(gains, dtype=float)
    if not len(a):
        return {"n": 0, "effective_n": 0, "mean": None, "interval": [None, None]}
    neff = effective_n(a)
    mean = float(a.mean())
    if neff <= 1 or len(a) < 2:
        interval = [None, None]
    else:
        se = float(np.std(a, ddof=1) / math.sqrt(neff))
        radius = float(student_t.ppf(1 - alpha / 2, max(1, neff - 1))) * se
        interval = [mean - radius, mean + radius]
    return {"n": len(a), "effective_n": neff, "mean": mean, "interval": interval}


def trial_metrics(rows, policy_id):
    trials = [r["trials"][policy_id] for r in rows]
    n = len(trials)
    if not n:
        return {"n": 0}
    stopped = sum(t["outcome"] == "STOPPED" for t in trials)
    ambiguous = sum(t["outcome"] == "AMBIGUOUS" for t in trials)
    targets = sum(t["outcome"] == "TARGET_HIT" for t in trials)
    neff = effective_n([t["net_lower"] for t in trials])
    return {
        "n": n,
        "effective_n": neff,
        "stopped": stopped,
        "ambiguous": ambiguous,
        "targets": targets,
        "stop_rate_bounds": [stopped / n, (stopped + ambiguous) / n],
        "target_rate_bounds": [targets / n, (targets + ambiguous) / n],
        "target_interval_95": [
            wilson(targets / n * neff, neff)[0],
            wilson((targets + ambiguous) / n * neff, neff)[1],
        ],
        "net_return_bounds": [
            float(np.mean([t["net_lower"] for t in trials])),
            float(np.mean([t["net_upper"] for t in trials])),
        ],
        "stop_distance_pct": float(np.mean([t["distance"] for t in trials])) * 100,
        "recovered_after_stop": sum(t["recovered_after_stop"] for t in trials),
        "mean_mae_pct": float(np.mean([r["mae"] for r in rows])) * 100,
        "mean_mfe_pct": float(np.mean([r["mfe"] for r in rows])) * 100,
    }


def research_stops(bars, cfg, round_no):
    contexts = {i: context_at(bars, i) for i in range(WARMUP, len(bars))}
    ids = [p["id"] for p in policies(cfg)]
    alpha = alpha_budget(round_no, cfg.stop_alpha / len(cfg.pairs))
    family = len(ids) * len(cfg.stop_horizons) * len(REGIMES)
    comparison_alpha = alpha / family
    reports, candidates, cutoffs_by_horizon = [], [], {}
    for horizon in cfg.stop_horizons:
        rows = []
        for i in range(WARMUP, len(bars) - horizon - 1):
            if (bars[i]["time"] // HOUR) % horizon:
                continue
            window = bars[i + 2 : i + horizon + 2]
            if any(
                b["time"] != bars[i]["time"] + (j + 2) * HOUR
                for j, b in enumerate(window)
            ):
                continue
            stops, target = levels(contexts[i], cfg)
            entry = window[0]["open"]
            rows.append(
                {
                    "time": bars[i]["time"] + HOUR,
                    "end": window[-1]["time"] + HOUR,
                    "context": contexts[i],
                    "trials": simulate(window, entry, stops, target, cfg),
                    "mae": min(b["low"] for b in window) / entry - 1,
                    "mfe": max(b["high"] for b in window) / entry - 1,
                }
            )
        train, valid, test = split(
            {
                "y": np.zeros(len(rows)),
                "time": np.array([r["time"] for r in rows]),
                "end": np.array([r["end"] for r in rows]),
            }
        )
        if not len(train):
            continue
        cutoffs = {
            key: float(np.median([rows[i]["context"][key] for i in train]))
            for key in ("atr_fraction", "volume_ratio")
        }
        cutoffs_by_horizon[str(horizon)] = cutoffs
        for r in rows:
            r["regime"] = regime(r["context"], cutoffs)
        for group in REGIMES:
            subsets = [
                [rows[i] for i in indices if rows[i]["regime"] == group]
                for indices in (train, valid, test)
            ]
            training, validation, testing = subsets
            if not training or not validation:
                continue
            chosen = max(
                ids,
                key=lambda pid: np.mean(
                    [r["trials"][pid]["net_lower"] for r in validation]
                ),
            )
            for pid in ids:
                train_gains = [
                    r["trials"][pid]["net_lower"] - r["trials"]["no-stop"]["net_upper"]
                    for r in training
                ]
                gains = [
                    r["trials"][pid]["net_lower"] - r["trials"]["no-stop"]["net_upper"]
                    for r in testing
                ]
                sd = float(np.std(train_gains, ddof=1)) if len(train_gains) > 1 else 1.0
                required = required_samples(sd, cfg, comparison_alpha)
                evidence = paired_summary(gains, comparison_alpha)
                metrics = trial_metrics(testing, pid)
                sufficient = evidence["effective_n"] >= required
                supported = (
                    sufficient
                    and evidence["interval"][0] is not None
                    and evidence["interval"][0] > 0
                    and evidence["mean"] >= cfg.stop_min_gain_bps / 10000
                )
                row = {
                    "horizon_hours": horizon,
                    "regime": group,
                    "policy_id": pid,
                    "status": "HISTORICAL_SUPPORT"
                    if supported
                    else "UNDERPOWERED"
                    if not sufficient
                    else "NOT_SUPPORTED",
                    "selected_on_validation": pid == chosen,
                    "required_samples": required,
                    "training_sd": sd,
                    "comparison_alpha": comparison_alpha,
                    "paired_gain": evidence,
                    "metrics": metrics,
                    "positive_net_expectation": bool(
                        metrics.get("net_return_bounds", [0])[0] > 0
                    ),
                    "train_n": len(training),
                    "validation_n": len(validation),
                    "split": {
                        "training_end": rows[int(train[-1])]["end"],
                        "validation_start": rows[int(valid[0])]["time"],
                        "test_start": rows[int(test[0])]["time"] if len(test) else None,
                    },
                }
                reports.append(row)
                if pid == chosen:
                    candidates.append(row)
    return {
        "round": round_no,
        "protocol": "long-stops-v1",
        "design_id": design(cfg),
        "round_alpha": alpha,
        "family_tests": family,
        "cutoffs": cutoffs_by_horizon,
        "candidates": candidates,
        "evaluations": reports,
        "design": {
            "total_alpha": cfg.stop_alpha,
            "power": cfg.power,
            "minimum_gain_bps": cfg.stop_min_gain_bps,
            "fee_bps": cfg.fee_bps,
            "slippage_bps": cfg.stop_slippage_bps,
            "target_atr": cfg.stop_target_atr,
            "max_stop_pct": cfg.stop_max_fraction * 100,
            "policies": policies(cfg),
            "automatic_orders": False,
            "scope": "long-only; common causal target across stop policies; separately budgeted stop research family",
        },
    }


def issue_session(pair, bars, report, cfg, horizon, issued_at, source):
    i = len(bars) - 1
    if i < WARMUP or (bars[i]["time"] // HOUR) % horizon:
        return None
    closed_at = bars[i]["time"] + HOUR
    if not closed_at <= issued_at <= closed_at + 600000:
        return None
    cutoffs = report.get("cutoffs", {}).get(str(horizon))
    if not cutoffs:
        return None
    context = context_at(bars, i)
    stops, target = levels(context, cfg)
    # Fixed one-hour buffer: every policy is known before entry, and scheduler
    # jitter cannot make consecutive same-horizon observation windows overlap.
    start = closed_at + HOUR
    group = regime(context, cutoffs)
    selected = next(
        (
            r["policy_id"]
            for r in report["candidates"]
            if r["horizon_hours"] == horizon and r["regime"] == group
        ),
        None,
    )
    return {
        "id": f"{pair}:{bars[i]['time']}:{horizon}:stops",
        "issued_at": issued_at,
        "reference_price": bars[i]["close"],
        "start_bar": start,
        "deadline_bar": start + (horizon - 1) * HOUR,
        "horizon_hours": horizon,
        "regime": group,
        "context": context,
        "policies": stops,
        "target_fraction": target,
        "design_id": report["design_id"],
        "research_round": report["round"],
        "selected_policy_id": selected,
        "fee_bps": cfg.fee_bps,
        "slippage_bps": cfg.stop_slippage_bps,
        "source": source,
        "entry": None,
        "trials": {},
        "mae": 0,
        "mfe": 0,
        "observed_through": None,
        "status": "PENDING",
        "aggregated": False,
    }


def settle_sessions(sessions, bars):
    by_time = {b["time"]: b for b in bars}
    last = bars[-1]["time"] if bars else -1
    for session in sessions:
        if session["status"] == "SETTLED":
            continue
        next_time = (
            session["observed_through"] + HOUR
            if session["observed_through"] is not None
            else session["start_bar"]
        )
        session["status"] = "PENDING"
        while next_time <= min(last, session["deadline_bar"]):
            bar = by_time.get(next_time)
            if bar is None:
                session["status"] = "DATA_GAP"
                break
            if session["entry"] is None:
                session["entry"] = bar["open"]
                session["trials"] = {
                    p["id"]: new_trial(p, session["entry"], session["target_fraction"])
                    for p in [*session["policies"], {"id": "no-stop", "distance": 0}]
                }
            entry = session["entry"]
            session["mae"] = min(session["mae"], bar["low"] / entry - 1)
            session["mfe"] = max(session["mfe"], bar["high"] / entry - 1)
            for trial in session["trials"].values():
                advance(
                    trial,
                    bar,
                    entry,
                    session["deadline_bar"],
                    session["fee_bps"],
                    session["slippage_bps"],
                )
            session["observed_through"] = next_time
            next_time += HOUR
        if session["observed_through"] == session["deadline_bar"]:
            session["status"] = "SETTLED"
    return sessions


def accumulate(sessions, lifetime):
    """Atomic state commits retain counters even after bounded raw-session retention."""
    for session in sessions:
        if session["status"] != "SETTLED" or session["aggregated"]:
            continue
        baseline = session["trials"]["no-stop"]
        for pid, trial in session["trials"].items():
            if pid == "no-stop":
                continue
            key = f"{session['source']}:{session['design_id']}:{session['horizon_hours']}:{pid}"
            total = lifetime.setdefault(
                key,
                {
                    "policy_id": pid,
                    "source": session["source"],
                    "design_id": session["design_id"],
                    "horizon_hours": session["horizon_hours"],
                    "n": 0,
                    "stopped": 0,
                    "targets": 0,
                    "ambiguous": 0,
                    "recovered_after_stop": 0,
                    "net_lower_sum": 0,
                    "net_upper_sum": 0,
                    "paired_gain_sum": 0,
                    "distance_sum": 0,
                    "mae_sum": 0,
                    "mfe_sum": 0,
                },
            )
            total["n"] += 1
            total["stopped"] += trial["outcome"] == "STOPPED"
            total["targets"] += trial["outcome"] == "TARGET_HIT"
            total["ambiguous"] += trial["outcome"] == "AMBIGUOUS"
            total["recovered_after_stop"] += trial["recovered_after_stop"]
            total["net_lower_sum"] += trial["net_lower"]
            total["net_upper_sum"] += trial["net_upper"]
            total["paired_gain_sum"] += trial["net_lower"] - baseline["net_upper"]
            total["distance_sum"] += trial["distance"]
            total["mae_sum"] += session["mae"]
            total["mfe_sum"] += session["mfe"]
        session["aggregated"] = True
    return lifetime


def forward_monitor(sessions, lifetime):
    rows = []
    for value in lifetime.values():
        n = value["n"]
        rows.append(
            {
                **value,
                "mean_stop_pct": value["distance_sum"] / n * 100,
                "mean_net_bounds": [
                    value["net_lower_sum"] / n,
                    value["net_upper_sum"] / n,
                ],
                "mean_paired_gain": value["paired_gain_sum"] / n,
            }
        )
    cohorts = {}
    for session in sessions:
        if session["status"] != "SETTLED":
            continue
        for pid in session["trials"]:
            if pid == "no-stop":
                continue
            key = (
                session["source"],
                session["design_id"],
                session["horizon_hours"],
                session["regime"],
                pid,
            )
            cohorts.setdefault(key, []).append(session)
    recent = []
    for (source, protocol, horizon, group, pid), cohort in cohorts.items():
        gains = [
            s["trials"][pid]["net_lower"] - s["trials"]["no-stop"]["net_upper"]
            for s in cohort
        ]
        summary = paired_summary(gains)
        recent.append(
            {
                "source": source,
                "design_id": protocol,
                "last_settled_at": max(s["deadline_bar"] + HOUR for s in cohort),
                "horizon_hours": horizon,
                "regime": group,
                "policy_id": pid,
                "paired_gain": summary,
                "metrics": trial_metrics(cohort, pid),
                "caution": bool(
                    summary["effective_n"] >= precision_n()
                    and summary["interval"][1] is not None
                    and summary["interval"][1] < 0
                ),
            }
        )
    return {
        "lifetime": rows,
        "recent": recent,
        "retained_sessions": len(sessions),
        "pending": sum(s["status"] != "SETTLED" for s in sessions),
        "data_gaps": sum(s["status"] == "DATA_GAP" for s in sessions),
        "status": "DESCRIPTIVE_FORWARD_COLLECTION",
        "warning": "OHLCV shadow outcomes, not exchange fills; cumulative n is not an independent sample count.",
    }


def manual_monitor(positions):
    stopped = [
        p for p in positions if p["stop"] is not None and p["state"] == "STOPPED"
    ]
    return {
        "with_stop": sum(p["stop"] is not None for p in positions),
        "stopped": len(stopped),
        "recovered_to_target": sum(
            p.get("followup_state") == "RECOVERED_TO_TARGET" for p in stopped
        ),
        "followup_pending": sum(
            p.get("followup_state", "PENDING") == "PENDING" for p in stopped
        ),
        "scope": "Manual paper positions; selection-biased, not used as randomized evidence.",
    }


def update_stops(
    state, bars, cfg, pair, issued_at, train=False, clock=None, allow_training=True
):
    current_design = design(cfg)
    report = state.get("stop_research", {})
    trained = allow_training and (
        train or not report or report.get("design_id") != current_design
    )
    if trained:
        report = research_stops(bars, cfg, report.get("round", 0) + 1)
        state.update(
            stop_research=report,
            stop_trained_at=issued_at,
            stop_trained_bar=bars[-1]["time"],
        )
    # Stamp plans after research, not at job start; long research cannot backdate
    # a prospective plan into the permitted issuance window.
    issued_at = clock() if clock is not None else issued_at
    if trained:
        state["stop_trained_at"] = issued_at
    sessions = settle_sessions(state.get("stop_sessions", []), bars)
    seen = {s["id"] for s in sessions}
    for horizon in cfg.stop_horizons if report else ():
        session = issue_session(
            pair, bars, report, cfg, horizon, issued_at, state["source"]
        )
        if session and session["id"] not in seen:
            sessions.append(session)
    lifetime = accumulate(sessions, state.get("stop_lifetime", {}))
    # Never discard an unresolved trial. Already aggregated sessions can be pruned.
    closed = [s for s in sessions if s["status"] == "SETTLED"][-cfg.max_forward :]
    pending = [s for s in sessions if s["status"] != "SETTLED"]
    state["stop_sessions"] = sorted([*closed, *pending], key=lambda s: s["issued_at"])
    state["stop_lifetime"] = lifetime
    state["stop_monitor"] = forward_monitor(state["stop_sessions"], lifetime)
    context = context_at(bars, len(bars) - 1)
    state["stop_context"] = context
    stop_levels, target = levels(context, cfg)
    by_id = {p["id"]: p for p in stop_levels}
    suggestions = []
    for horizon in cfg.stop_horizons:
        cutoffs = report.get("cutoffs", {}).get(str(horizon))
        if not cutoffs:
            continue
        group = regime(context, cutoffs)
        candidate = next(
            (
                r
                for r in report["candidates"]
                if r["horizon_hours"] == horizon and r["regime"] == group
            ),
            None,
        )
        if not candidate:
            continue
        distance = by_id[candidate["policy_id"]]["distance"]
        price = bars[-1]["close"]
        recent = next(
            (
                r
                for r in state["stop_monitor"]["recent"]
                if r["design_id"] == current_design
                and r["source"] == state["source"]
                and r["horizon_hours"] == horizon
                and r["regime"] == group
                and r["policy_id"] == candidate["policy_id"]
            ),
            None,
        )
        suggestions.append(
            {
                "horizon_hours": horizon,
                "regime": group,
                "policy_id": candidate["policy_id"],
                "status": "FORWARD_CAUTION"
                if recent and recent["caution"]
                else candidate["status"],
                "reference_price": price,
                "stop_price": price * (1 - distance),
                "distance_pct": distance * 100,
                "target_price": price * (1 + target),
                "risk_reward": target / distance,
                "required_samples": candidate["required_samples"],
                "effective_samples": candidate["paired_gain"]["effective_n"],
                "historical_metrics": candidate["metrics"],
                "forward_samples": recent["paired_gain"]["n"] if recent else 0,
                "forward_effective_samples": recent["paired_gain"]["effective_n"]
                if recent
                else 0,
                "automatic_apply": False,
            }
        )
    state["stop_suggestions"] = suggestions
    return "BACKFILLING" if state["stop_monitor"]["data_gaps"] else "OK"


def public_stops(state):
    report = state.get("stop_research", {})
    return {
        "trained_at": state.get("stop_trained_at"),
        "context": state.get("stop_context", {}),
        "research": {k: v for k, v in report.items() if k != "evaluations"},
        "suggestions": copy.deepcopy(state.get("stop_suggestions", [])),
        "forward": state.get("stop_monitor", {}),
        "manual": state.get("stop_manual", {}),
        "warning": "Araştırma adayıdır; otomatik emir/stop değişimi yok. Hedef, süre ve rejimle birlikte değerlendirilir.",
    }
