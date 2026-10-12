import copy
import math
import numpy as np
from .config import HOUR, VERSION
from .features import NAMES, WARMUP, base_at, matrix
from .market import ingest
from .research import predict, research
from .statistics import calibration, effective_n
from .storage import clock_ms
from .interpretation import interpret
from .stops import manual_monitor, public_stops, update_stops, paired_summary
from .plans import build_plan
from .entries import update as update_entries, public_report as public_entries


def settle_forecasts(records, bars):
    by_time = {b["time"]: b for b in bars}
    for record in records:
        if record.get("outcome") is not None:
            continue
        end = by_time.get(record["end_bar"])
        if end:
            move = end["close"] / record["price"] - 1
            record["observed_return"] = move
            record["outcome"] = int(
                move >= record["threshold"]
                if record["direction"] == "up"
                else move <= -record["threshold"]
            )
            record["settled_at"] = end["time"] + HOUR
    return records


def monitor(records):
    reports = []
    for horizon in (1, 6, 24):
        for direction in ("up", "down"):
            rows = [
                r
                for r in records
                if r["horizon_hours"] == horizon
                and r["direction"] == direction
                and r.get("outcome") is not None
            ][-200:]
            if not rows:
                continue
            y = np.array([r["outcome"] for r in rows])
            p = np.array([r["probability"] for r in rows])
            reference = np.array([r["baseline"] for r in rows])
            loss_delta = (p - y) ** 2 - (reference - y) ** 2
            neff = effective_n(loss_delta)
            se = (
                float(np.std(loss_delta, ddof=1) / math.sqrt(neff))
                if len(rows) > 1
                else 1
            )
            # Drift is an operational warning, not another profitability significance claim.
            drift = neff >= 50 and float(np.mean(loss_delta)) - 2.58 * se > 0
            reports.append(
                {
                    "horizon_hours": horizon,
                    "direction": direction,
                    "calibration": calibration(y, p),
                    "effective_n": neff,
                    "baseline_brier": float(np.mean((reference - y) ** 2)),
                    "drift": bool(drift),
                    "recent_window": len(rows),
                }
            )
    # Score maturity must not pool baseline forecasts or different model versions.
    cohorts = {}
    for r in records:
        if r.get("outcome") is not None and r.get("model_version") not in (
            None,
            "baseline",
        ):
            cohorts.setdefault(
                (r["horizon_hours"], r["direction"], r["model_version"]), []
            ).append(r)
    model_streams = []
    for (horizon, direction, version), values in cohorts.items():
        rows = sorted(values, key=lambda r: r["bar_time"])[-200:]
        gains = [
            (r["baseline"] - r["outcome"]) ** 2 - (r["probability"] - r["outcome"]) ** 2
            for r in rows
        ]
        stats = paired_summary(gains, 0.05 / 6)
        model_streams.append(
            {
                "horizon_hours": horizon,
                "direction": direction,
                "model_version": version,
                "effective_n": stats["effective_n"],
                "gain_lower_bound": stats["interval"][0],
                "gain_interval": stats["interval"],
                "calibration": calibration(
                    [r["outcome"] for r in rows], [r["probability"] for r in rows]
                ),
                "last_settled_at": max(r.get("settled_at", 0) for r in rows),
            }
        )
    return {
        "model_streams": model_streams,
        "streams": reports,
        "drift": any(r["drift"] for r in reports),
        "settled": sum(r.get("outcome") is not None for r in records),
        "pending": sum(r.get("outcome") is None for r in records),
    }


def public_payload(pair, state):
    bars = state["bars"]
    report = state.get("research", {})
    return {
        "pair": pair,
        "display": pair[:-3] + "/TRY",
        "coin": pair[:-3],
        "price": bars[-1]["close"] if bars else None,
        "observed_at": bars[-1]["time"] + HOUR if bars else None,
        "as_of": state["completed_at"],
        "version": VERSION,
        "backfill_complete": state["backfill_complete"],
        "source": state.get("source", "Binance TR"),
        "bars": len(bars),
        "forecasts": state.get("current_forecasts", []),
        "monitor": state.get("monitor", {}),
        "trained_at": state.get("trained_at"),
        "score_models": [
            {
                "model_version": m.get("version"),
                "horizon_hours": m.get("horizon_hours"),
                "direction": m.get("direction"),
                "acceptance": m.get("score_acceptance", {}),
            }
            for m in report.get("models", {}).values()
        ],
        "features": state.get("latest_features", {}),
        "interpretation": interpret(state.get("latest_features", {})),
        "research": {
            k: v for k, v in report.items() if k not in ("models", "hypotheses")
        },
        "hypotheses": report.get("hypotheses", [])[:40],
        "hypothesis_count": len(report.get("hypotheses", [])),
        "chart": {"hour": bars[-120:], "as_of": state["completed_at"]},
        "stages": state["stages"],
        "stop_loss": public_stops(state),
        "trade_plan": state.get("trade_plan"),
        "entry_research": public_entries(state),
    }


def run_pair(
    store,
    market,
    cfg,
    pair,
    job,
    now=None,
    force_train=False,
    clock=clock_ms,
    allow_training=True,
):
    now = clock() if now is None else now
    token = store.acquire(pair, job, now)
    if token is None:
        return {"pair": pair, "status": "BUSY"}
    try:
        state = copy.deepcopy(store.read(pair) or {})
        bars, complete = ingest(market, pair, state, now, cfg)
        state.update(
            bars=bars,
            backfill_complete=complete,
            source=getattr(market, "source", "TEST_FIXTURE"),
        )
        stages = {
            "acquisition": "OK" if complete else "BACKFILLING",
            "features": "WAITING",
            "labels": "WAITING",
            "research": "WAITING",
            "forward_monitor": "WAITING",
            "paper_tracking": "WAITING",
            "stop_research": "WAITING",
            "stop_forward_monitor": "WAITING",
            "publication": "OK",
        }
        state["forward"] = settle_forecasts(state.get("forward", []), bars)
        state["monitor"] = monitor(state["forward"])
        stages["labels"] = stages["forward_monitor"] = "OK"
        state["current_forecasts"] = []
        if complete and len(bars) > WARMUP + 30:
            needs_training = (
                not state.get("research")
                or (
                    now - state.get("trained_at", 0) >= cfg.retrain_ms
                    or force_train
                    or state["monitor"]["drift"]
                )
                and state.get("trained_bar") != bars[-1]["time"]
            )
            training_deferred = needs_training and not allow_training
            needs_training = needs_training and allow_training
            if needs_training:
                indices, x = matrix(bars)
            else:
                indices = np.array([len(bars) - 1])
                x = np.atleast_2d(
                    np.concatenate(
                        [base_at(bars, len(bars) - 1 - lag) for lag in (0, 1, 3, 6)]
                    )
                )
            stages["features"] = "OK"
            state["latest_features"] = dict(zip(NAMES, x[-1].tolist()))
            if needs_training:
                previous = state.get("research", {})
                result = research(
                    bars,
                    indices,
                    x,
                    cfg,
                    previous.get("round", 0) + 1,
                    previous.get("models"),
                )
                state.update(
                    research=result, trained_at=now, trained_bar=bars[-1]["time"]
                )
            stages["research"] = (
                "WAITING_CHARGE"
                if training_deferred
                else "FAILED"
                if any(
                    r["status"] == "OPTIMIZER_FAILED"
                    for r in state.get("research", {}).get("evaluations", [])
                )
                else "OK"
            )
            report = state.get("research", {})
            evaluations = {
                (r["horizon_hours"], r.get("direction")): r
                for r in report.get("evaluations", [])
            }
            for horizon in cfg.horizons:
                vol = x[-1, NAMES.index("volatility_24@lag0")]
                threshold = float(
                    max(cfg.min_move, vol * np.sqrt(horizon), cfg.fee_bps / 10000)
                )
                for direction in ("up", "down"):
                    model = report.get("models", {}).get(f"{horizon}:{direction}")
                    evaluation = evaluations.get((horizon, direction), {})
                    baseline = evaluation.get("baseline_rate")
                    if baseline is None:
                        continue
                    drift = state["monitor"]["drift"]
                    probability = (
                        float(predict(model, x[-1])[0])
                        if model and not drift
                        else baseline
                    )
                    record = {
                        "id": f"{pair}:{bars[-1]['time']}:{horizon}:{direction}",
                        "pair": pair,
                        "bar_time": bars[-1]["time"],
                        "issued_at": clock(),
                        "end_bar": bars[-1]["time"] + horizon * HOUR,
                        "horizon_hours": horizon,
                        "direction": direction,
                        "threshold": threshold,
                        "price": bars[-1]["close"],
                        "probability": probability,
                        "baseline": baseline,
                        "model_version": model["version"]
                        if model and not drift
                        else "baseline",
                        "status": "MODEL_ESTIMATE"
                        if model and not drift
                        else "DRIFT_PAUSED"
                        if drift
                        else "BASELINE_ONLY",
                        "outcome": None,
                    }
                    state["current_forecasts"].append(record)
                    # Keep the first issuance immutable. Late scans cannot claim predictions before an event.
                    # Non-overlapping forward horizon anchors; don't record backfilled historical forecasts.
                    on_time = (
                        record["issued_at"] - (bars[-1]["time"] + HOUR) <= 2 * 300000
                    )
                    independent = (bars[-1]["time"] // HOUR) % horizon == 0
                    if (
                        on_time
                        and independent
                        and record["id"] not in {r["id"] for r in state["forward"]}
                    ):
                        state["forward"].append(record)
            # Project mutually exclusive directional estimates onto their valid probability region.
            # Newly issued forward records share these objects; their scored probability is the emitted value.
            for horizon in cfg.horizons:
                streams = [
                    f
                    for f in state["current_forecasts"]
                    if f["horizon_hours"] == horizon
                ]
                total = sum(f["probability"] for f in streams)
                if total > 1 and len(streams) == 2:
                    for record in streams:
                        record["raw_probability"] = record["probability"]
                        record["probability"] -= (total - 1) / 2
                        record["coherence_projected"] = True
            state["forward"] = state["forward"][-cfg.max_forward :]
            state["monitor"] = monitor(state["forward"])
            stop_train = (
                not state.get("stop_research")
                or (
                    force_train
                    or now - state.get("stop_trained_at", 0) >= cfg.retrain_ms
                )
                and state.get("stop_trained_bar") != bars[-1]["time"]
            )
            stop_status = update_stops(
                state,
                bars,
                cfg,
                pair,
                clock(),
                train=stop_train and allow_training,
                clock=clock,
                allow_training=allow_training,
            )
            stages["stop_research"] = (
                "WAITING_CHARGE" if stop_train and not allow_training else "OK"
            )
            stages["stop_forward_monitor"] = stop_status
        from .paper import track_pair

        paper_status = track_pair(store, market, pair, now, max_pages=cfg.max_pages)
        stages["paper_tracking"] = paper_status
        state["stop_manual"] = manual_monitor(
            [p for p in store.positions() if p["pair"] == pair]
        )
        stages["entry_research"] = update_entries(
            state,
            bars,
            cfg,
            pair,
            clock(),
            allow_training=allow_training,
            force=force_train,
        )
        state["stages"] = stages
        state["completed_at"] = clock()
        state["trade_plan"] = build_plan(
            pair,
            bars,
            bars[-1]["close"] if bars else None,
            bars[-1]["time"] + HOUR if bars else None,
            state["completed_at"],
            cfg,
            public_stops(state),
            entry_research=state.get("entry_research"),
        )
        # Real time completion is required for lease fencing even with a fixed acquisition cutoff.
        store.commit(pair, token, state, public_payload(pair, state), clock())
        return {
            "pair": pair,
            "status": "COMPLETED",
            "backfill_complete": complete,
            "stages": stages,
        }
    except Exception as exc:
        store.fail(pair, token, exc, clock())
        raise
