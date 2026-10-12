"""Bounded research overlay on the unchanged descriptive Rally Score.

Maturity gates reuse historical evidence and version-specific forward outcomes.
This index mapping is a product rule, not a trained probability or profit claim.
Reads can withdraw obsolete evidence without a new exchange request.
"""

import copy
import math

from .config import HOUR, PERIOD, score_direction_design as direction_design
from .entries import compatible as entry_compatible
from .statistics import precision_n
from .stops import design as stop_design

LIMITS = {"direction": 14.0, "entry": 3.0, "stop": 3.0}
PROTOCOL = "mature-research-rally-v1"


def numeric(value):
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(value)
    )


def fresh(at, now, maximum):
    return numeric(at) and 0 < at <= now and now - at <= maximum


def bound(value):
    return max(-1.0, min(1.0, value))


def quality(effective, required):
    return effective / (effective + required)


def pending(family, reason, **details):
    return {
        "family": family,
        "status": "WAITING",
        "reason": reason,
        "points": 0.0,
        "limit": LIMITS[family],
        **details,
    }


def direction(payload, cfg, pair, now):
    result = pending("direction", "MODEL_EVIDENCE_PENDING", streams=[])
    if payload.get("monitor", {}).get("drift"):
        return {**result, "status": "PAUSED", "reason": "MODEL_DRIFT"}
    if not fresh(payload.get("trained_at"), now, 2 * cfg.retrain_ms):
        return {**result, "reason": "MODEL_RESEARCH_STALE"}
    if payload.get("stages", {}).get("research") == "FAILED":
        return {**result, "status": "PAUSED", "reason": "MODEL_TRAINING_FAILED"}
    models = {
        (r.get("horizon_hours"), r.get("direction"), r.get("model_version")): r.get(
            "acceptance", {}
        )
        for r in payload.get("score_models", [])
    }
    monitors = {
        (r.get("horizon_hours"), r.get("direction"), r.get("model_version")): r
        for r in payload.get("monitor", {}).get("model_streams", [])
    }
    required = precision_n(cfg.ci_half_width)
    strengths = []
    for forecast in payload.get("forecasts", []):
        h, side, version = (
            forecast.get("horizon_hours"),
            forecast.get("direction"),
            forecast.get("model_version"),
        )
        if h not in cfg.horizons or side not in ("up", "down"):
            continue
        key = (h, side, version)
        cert, observed = models.get(key, {}), monitors.get(key, {})
        row = {
            "horizon_hours": h,
            "direction": side,
            "model_version": version,
            "status": "WAITING",
            "reason": "HISTORICAL_MODEL_PENDING",
            "required_samples": required,
            "effective_samples": observed.get("effective_n", 0),
        }
        p, base = forecast.get("probability"), forecast.get("baseline")
        if (
            forecast.get("pair") != pair
            or forecast.get("status") != "MODEL_ESTIMATE"
            or not numeric(p)
            or not 0 <= p <= 1
            or not numeric(base)
            or not 0 <= base <= 1
            or not fresh(forecast.get("issued_at"), now, HOUR)
            or forecast.get("bar_time", -HOUR) + HOUR != now // HOUR * HOUR
            or forecast.get("end_bar", 0) + HOUR <= now
        ):
            row["reason"] = "FORECAST_STALE"
        elif (
            cert.get("design") != direction_design(cfg)
            or cert.get("model_version") != version
            or not numeric(cert.get("gain_lower_bound"))
            or cert["gain_lower_bound"] <= 0
            or not numeric(cert.get("effective_test_samples"))
            or cert["effective_test_samples"] < required
            or not numeric(cert.get("calibration_ece"))
            or cert["calibration_ece"] > cfg.ci_half_width
        ):
            pass
        elif not fresh(
            observed.get("last_settled_at"), now, 2 * cfg.retrain_ms + h * HOUR
        ):
            row["reason"] = "FORWARD_RESULTS_STALE"
        elif (
            not numeric(observed.get("effective_n"))
            or observed["effective_n"] < required
        ):
            row["reason"] = "FORWARD_SAMPLES_PENDING"
        elif (
            not numeric(observed.get("gain_lower_bound"))
            or observed["gain_lower_bound"] <= 0
            or not numeric(observed.get("calibration", {}).get("ece"))
            or observed["calibration"]["ece"] > cfg.ci_half_width
        ):
            row.update(status="PAUSED", reason="FORWARD_QUALITY_FAILED")
        else:
            strength = bound((p - base) / cfg.detectable_lift) * (
                1 if side == "up" else -1
            )
            weight = quality(
                min(cert["effective_test_samples"], observed["effective_n"]), required
            )
            strengths.append(strength * weight)
            row.update(
                status="MATURE",
                reason="HISTORICAL_AND_FORWARD_SUPPORT",
                probability=p,
                baseline=base,
                reliability_weight=weight,
                signed_strength=strength,
            )
        result["streams"].append(row)
    if strengths:
        result.update(
            status="MATURE",
            reason="HISTORICAL_AND_FORWARD_SUPPORT",
            points=LIMITS["direction"] * sum(strengths) / len(strengths),
        )
    elif any(r["status"] == "PAUSED" for r in result["streams"]):
        result.update(status="PAUSED", reason="FORWARD_QUALITY_FAILED")
    return result


def entry(payload, cfg, pair, now):
    report = payload.get("entry_research", {})
    result = pending(
        "entry",
        "ENTRY_EVIDENCE_PENDING",
        effective_samples=report.get("effective_samples", 0),
        required_samples=report.get("required_samples"),
    )
    if (
        not entry_compatible(report, cfg, now)
        or report.get("pair") != pair
        or not fresh(report.get("trained_at"), now, 2 * cfg.retrain_ms)
    ):
        return result
    req = report.get("required_samples")
    data = report.get("test", {})
    neff = min(
        data.get("net", {}).get("effective_n", 0),
        data.get("gain", {}).get("effective_n", 0),
    )
    result["effective_samples"] = neff
    if not numeric(req) or req < precision_n(cfg.ci_half_width) or neff < req:
        return result
    gain = data.get("gain", {}).get("interval", [None, None])
    net = data.get("net", {}).get("interval", [None, None])
    if len(gain) != 2 or len(net) != 2:
        return result
    sign = 0
    if (
        report.get("status") == "HISTORICAL_SUPPORT"
        and numeric(gain[0])
        and gain[0] > cfg.entry_min_gain_bps / 10000
        and numeric(net[0])
        and net[0] > 0
    ):
        sign = 1
    elif (
        report.get("status") == "NOT_SUPPORTED"
        and numeric(gain[1])
        and gain[1] < -cfg.entry_min_gain_bps / 10000
        and numeric(net[1])
        and net[1] < 0
    ):
        sign = -1
    if not sign:
        return {**result, "reason": "ENTRY_NO_CLEAR_ADVANTAGE"}
    plan = payload.get("trade_plan", {}) or {}
    if (
        plan.get("status") != "READY"
        or plan.get("entry_policy_id") != report.get("policy_id")
        or plan.get("entry_research", {}).get("design") != report.get("design")
        or plan.get("entry_research", {}).get("round") != report.get("round")
    ):
        return {**result, "status": "PAUSED", "reason": "ENTRY_PLAN_NOT_CURRENT"}
    return {
        **result,
        "status": "MATURE",
        "reason": "ENTRY_HISTORICAL_SUPPORT" if sign > 0 else "ENTRY_HISTORICAL_LOSS",
        "policy_id": report["policy_id"],
        "points": sign * LIMITS["entry"] * quality(neff, req),
        "evidence_kind": "HISTORICAL_PAPER",
    }


def stop(payload, cfg, now):
    data = payload.get("stop_loss", {})
    result = pending("stop", "STOP_EVIDENCE_PENDING")
    if not fresh(data.get("trained_at"), now, 2 * cfg.retrain_ms) or data.get(
        "research", {}
    ).get("design_id") != stop_design(cfg):
        return result
    if data.get("forward", {}).get("data_gaps", 0):
        return {**result, "status": "PAUSED", "reason": "STOP_FORWARD_GAP"}
    required_forward = precision_n(cfg.ci_half_width)
    weights, details = [], []
    for suggestion in data.get("suggestions", []):
        if suggestion.get("status") != "HISTORICAL_SUPPORT":
            continue
        key = tuple(suggestion.get(k) for k in ("horizon_hours", "regime", "policy_id"))
        historical = next(
            (
                r
                for r in data["research"].get("candidates", [])
                if tuple(r.get(k) for k in ("horizon_hours", "regime", "policy_id"))
                == key
            ),
            {},
        )
        forward = next(
            (
                r
                for r in data.get("forward", {}).get("recent", [])
                if tuple(r.get(k) for k in ("horizon_hours", "regime", "policy_id"))
                == key
                and r.get("design_id") == stop_design(cfg)
                and r.get("source") == payload.get("source")
            ),
            {},
        )
        hstat, fstat = historical.get("paired_gain", {}), forward.get("paired_gain", {})
        required = historical.get("required_samples")
        hlo, flo = (
            hstat.get("interval", [None, None])[0],
            fstat.get("interval", [None, None])[0],
        )
        if (
            historical.get("status") != "HISTORICAL_SUPPORT"
            or not numeric(required)
            or required < required_forward
            or hstat.get("effective_n", 0) < required
            or not numeric(hlo)
            or hlo <= 0
            or not fresh(
                forward.get("last_settled_at"), now, 2 * cfg.retrain_ms + key[0] * HOUR
            )
            or fstat.get("effective_n", 0) < required_forward
            or not numeric(flo)
            or flo <= 0
            or forward.get("caution")
        ):
            continue
        weights.append(
            min(
                quality(hstat["effective_n"], required),
                quality(fstat["effective_n"], required_forward),
            )
        )
        details.append(
            {
                "horizon_hours": key[0],
                "regime": key[1],
                "policy_id": key[2],
                "historical_effective_samples": hstat["effective_n"],
                "historical_required_samples": required,
                "forward_effective_samples": fstat["effective_n"],
                "forward_required_samples": required_forward,
            }
        )
    if weights:
        result.update(
            status="MATURE",
            reason="STOP_HISTORICAL_AND_FORWARD_SUPPORT",
            points=LIMITS["stop"] * sum(weights) / len(weights),
            candidates=details,
        )
    return result


def blend(analysis, payload, cfg, pair, market_at, now, error=None):
    result = copy.deepcopy(analysis or {})
    base = result.get("base_score", result.get("score"))
    if not numeric(base) or not 0 <= base <= 100:
        return result
    payload = payload or {}
    reason = None
    if error or not fresh(market_at, now, 3 * PERIOD):
        reason = "MARKET_STALE"
    elif payload.get("pair") != pair or not payload.get("backfill_complete"):
        reason = "HISTORY_PENDING"
    elif payload.get("source") == "SYNTHETIC_DEMO":
        reason = "DEMO_EVIDENCE"
    elif payload.get("observed_at") != now // HOUR * HOUR:
        reason = "RESEARCH_CANDLES_BEHIND"
    if reason:
        families = [pending(f, reason) for f in LIMITS]
    else:
        families = [
            direction(payload, cfg, pair, now),
            entry(payload, cfg, pair, now),
            stop(payload, cfg, now),
        ]
    delta = max(
        -sum(LIMITS.values()),
        min(sum(LIMITS.values()), sum(r["points"] for r in families)),
    )
    score = round(max(0, min(100, base + delta)))
    result.update(
        base_score=base,
        score=score,
        research_adjustment=score - base,
        research_overlay={
            "protocol": PROTOCOL,
            "status": "ACTIVE"
            if any(r["status"] == "MATURE" for r in families)
            else "PAUSED"
            if any(r["status"] == "PAUSED" for r in families)
            or reason in ("MARKET_STALE", "RESEARCH_CANDLES_BEHIND")
            else "WAITING",
            "evaluated_at": now,
            "market_at": market_at,
            "research_at": payload.get("as_of"),
            "requested_points": delta,
            "maximum_points": sum(LIMITS.values()),
            "families": families,
            "probability": False,
            "warning": "Araştırma katkılı puan bir endekstir; başarı olasılığı veya kârlılık kanıtı değildir.",
        },
    )
    result["method"] = PROTOCOL
    result["probability"] = False
    return result
