"""Fixed closed-bar entry prices and separately timestamped live quotes."""

import copy
import hashlib
import json
import math

from .config import HOUR, PERIOD
from .statistics import precision_n
from .entries import POLICIES, compatible, context, entry_level, risk_levels


def waiting(pair, reason):
    return {
        "pair": pair,
        "status": "WAITING",
        "reason": reason,
        "entry": None,
        "target": None,
        "stop": None,
        "automatic_apply": False,
    }


def build_plan(
    pair,
    bars,
    reference_price,
    price_at,
    now,
    cfg,
    stops=None,
    spread_pct=0,
    entry_research=None,
):
    if (
        not isinstance(reference_price, (int, float))
        or not math.isfinite(reference_price)
        or reference_price <= 0
    ):
        return waiting(pair, "Alış ve stop önerisi için fiyat verisi bekleniyor.")
    closed = [b for b in bars if b["time"] + HOUR <= now]
    if len(closed) < 24:
        return waiting(
            pair,
            "Alış ve ATR stop önerisi için en az 24 kapalı saatlik mum bekleniyor.",
        )
    if any(
        closed[i + 1]["time"] - closed[i]["time"] != HOUR
        for i in range(len(closed) - 1)
    ):
        return waiting(pair, "Mum verisinde boşluk var; fiyat önerisi güncellenemedi.")
    if any(
        not all(
            math.isfinite(b[k]) and b[k] > 0 for k in ("open", "high", "low", "close")
        )
        or b["low"] > min(b["open"], b["close"])
        or b["high"] < max(b["open"], b["close"])
        for b in closed
    ):
        return waiting(pair, "Fiyat önerisi için OHLC verisi doğrulanamadı.")
    friction = max(
        0.0001,
        (cfg.fee_bps + 2 * cfg.stop_slippage_bps) / 10000 + max(0, spread_pct) / 200,
    )
    if (
        not math.isfinite(spread_pct)
        or spread_pct < 0
        or friction >= cfg.stop_max_fraction
    ):
        return waiting(pair, "Spread/maliyet fiyat planının risk sınırını aşıyor.")
    ctx = context(closed, len(closed) - 1)
    report = (
        entry_research
        if compatible(entry_research, cfg, now)
        and entry_research.get("pair") == pair
        and entry_research.get("trained_bar", now) <= closed[-1]["time"]
        else {}
    )
    entry_policy = report.get("policy_id", "atr:1")
    if entry_policy not in POLICIES:
        entry_policy = "atr:1"
        report = {}
    entry = entry_level(entry_policy, ctx, cfg)
    discount = 1 - entry / ctx["anchor"]
    distance, target_fraction = risk_levels(ctx, cfg)
    distance = min(cfg.stop_max_fraction, max(friction, distance))
    atr = ctx["atr_fraction"]
    policy, basis = "atr:1.5", "ATR_BASELINE"
    candidate = next(
        (
            row
            for row in (stops or {}).get("suggestions", [])
            if row.get("horizon_hours") == 48
            and row.get("status") == "HISTORICAL_SUPPORT"
        ),
        None,
    )
    if candidate and not report:
        learned = candidate.get("distance_pct", 0) / 100
        if math.isfinite(learned) and 0 < learned <= cfg.stop_max_fraction:
            distance = max(friction, learned)
            distance = min(cfg.stop_max_fraction, distance)
            policy, basis = candidate["policy_id"], "RESEARCH_DISTANCE_ADAPTED"
    evaluation = report.get("test", {})
    effective = report.get("effective_samples", 0)
    required = report.get(
        "required_samples", precision_n(cfg.ci_half_width, cfg.entry_alpha)
    )
    rate = evaluation.get("fill_rate")
    plan = {
        "pair": pair,
        "method": "closed-limit-entry-v2",
        "anchor_price": ctx["anchor"],
        "entry_policy_id": entry_policy,
        "entry_basis": "HISTORICAL_COMPARISON" if report else "ATR_PRELIMINARY",
        "entry_research": report,
        "entry": entry,
        "stop": entry * (1 - distance),
        "target": entry * (1 + target_fraction),
        "as_of": now,
        "closed_at": closed[-1]["time"] + HOUR,
        "discount_fraction": discount,
        "stop_fraction": distance,
        "target_fraction": target_fraction,
        "atr_fraction": atr,
        "entry_horizon_hours": 6,
        "exit_horizon_hours": 48,
        "training_samples": report.get("training_samples", 0),
        "evaluation_samples": report.get("evaluation_samples", 0),
        "effective_samples": effective,
        "required_samples": required,
        "entry_touch_rate": rate,
        "entry_touch_interval": evaluation.get("fill_interval", [None, None]),
        "entry_probability": rate
        if effective >= required and evaluation.get("fill_effective_n", 0) >= required
        else None,
        "evidence_status": report.get("status", "UNDERPOWERED"),
        "stop_policy_id": policy,
        "stop_basis": basis,
        "automatic_apply": False,
        "plan_profitability_validated": False,
        "record_semantics": "ASSUME_BOUGHT_NOW",
        "note": "Alış seviyesi son kapalı mumda sabitlenir. "
        + (
            "Geçmiş aday karşılaştırması; gelecek için optimum doğrulanmadı."
            if report
            else "Araştırma tamamlanana kadar ATR ön adayı; optimum doğrulanmadı."
        ),
    }
    return rebase(plan, reference_price, price_at, now)


def rebase(plan, reference_price, price_at, now):
    result = copy.deepcopy(plan)
    if result.get("entry") is None and "discount_fraction" not in result:
        return result
    if (
        not isinstance(reference_price, (int, float))
        or not math.isfinite(reference_price)
        or reference_price <= 0
    ):
        return waiting(result["pair"], "Güncel fiyat bekleniyor.")
    result.update(reference_price=reference_price, price_at=price_at, as_of=now)
    if result.get("method") != "closed-limit-entry-v2":
        result["status"] = "STALE"
        result["reason"] = "Eski alış hesabı; yeni kapalı mum analizi bekleniyor."
        return result
    result["quote_distance_fraction"] = 1 - result["entry"] / reference_price
    result["risk_reward"] = result["target_fraction"] / result["stop_fraction"]
    result["expires_at"] = (price_at or 0) + 3 * PERIOD
    identity = {
        k: result.get(k)
        for k in (
            "pair",
            "method",
            "closed_at",
            "entry_policy_id",
            "entry",
            "stop",
            "target",
            "stop_basis",
        )
    }
    identity["research_round"] = result.get("entry_research", {}).get("round")
    identity["research_design"] = result.get("entry_research", {}).get("design")
    result["id"] = hashlib.sha256(
        json.dumps(identity, sort_keys=True, allow_nan=False).encode()
    ).hexdigest()[:24]
    return current_plan(result, now)


def current_plan(plan, now):
    result = copy.deepcopy(plan)
    if result.get("entry") is None:
        return result
    if result.get("method") != "closed-limit-entry-v2":
        result["status"] = "STALE"
        result["reason"] = "Eski alış hesabı; yeni kapalı mum analizi bekleniyor."
        return result
    result["reason"] = None
    result["status"] = (
        "READY"
        if result.get("price_at")
        and result["price_at"] <= now <= result["expires_at"]
        and result.get("closed_at", 0) <= now
        and result.get("closed_at", 0) >= now // HOUR * HOUR
        else "STALE"
    )
    if (
        result["status"] == "READY"
        and result.get("reference_price", 0) <= result["entry"]
    ):
        result["status"] = "STALE"
        result["reason"] = (
            "Fiyat alış seviyesine ulaştı veya altına indi; yeni analiz bekleniyor."
        )
    return result
