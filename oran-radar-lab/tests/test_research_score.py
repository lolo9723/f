import copy
import json
import sqlite3
from dataclasses import replace

import pytest

from conftest import make_bars
from radar import phone
from radar.config import Config, HOUR
from radar.entries import research as entry_research
from radar.pipeline import monitor
from radar.research_score import blend, direction_design
from radar.stops import design as stop_design

PAIR = "BTCTRY"
NOW = 500 * HOUR + 1000


def forward_rows(version="v1", count=120, direction="up", start=100):
    return [
        {
            "pair": PAIR,
            "horizon_hours": 1,
            "direction": direction,
            "model_version": version,
            "bar_time": (start + i) * HOUR,
            "probability": 0.9,
            "baseline": 0.5,
            "outcome": int(i % 10 != 0),
            "settled_at": (start + i + 2) * HOUR,
        }
        for i in range(count)
    ]


def mature(cfg=None, side="up"):
    cfg = cfg or Config(pairs=(PAIR,))
    rows = forward_rows(direction=side, start=370)
    monitor_value = monitor(rows)
    payload = {
        "pair": PAIR,
        "source": "Binance TR",
        "observed_at": 500 * HOUR,
        "as_of": NOW,
        "backfill_complete": True,
        "trained_at": NOW,
        "stages": {"research": "OK"},
        "monitor": monitor_value,
        "score_models": [
            {
                "horizon_hours": 1,
                "direction": side,
                "model_version": "v1",
                "acceptance": {
                    "design": direction_design(cfg),
                    "model_version": "v1",
                    "gain_lower_bound": 0.03,
                    "effective_test_samples": 194,
                    "calibration_ece": 0.02,
                },
            }
        ],
        "forecasts": [
            {
                "pair": PAIR,
                "horizon_hours": 1,
                "direction": side,
                "model_version": "v1",
                "status": "MODEL_ESTIMATE",
                "probability": 0.8,
                "baseline": 0.5,
                "bar_time": 499 * HOUR,
                "issued_at": NOW,
                "end_bar": 500 * HOUR,
            }
        ],
    }
    return payload, cfg


def score(payload, cfg, base=60, now=NOW, error=None):
    return blend({"score": base}, payload, cfg, PAIR, now, now, error)


def test_immature_research_flags_and_ten_scans_cannot_change_original_score():
    data, cfg = mature()
    data["monitor"] = monitor(forward_rows(count=10, start=480))
    for _ in range(10):
        result = score(data, cfg)
        assert result["score"] == result["base_score"] == 60
        assert result["research_adjustment"] == 0
        assert result["research_overlay"]["status"] == "WAITING"
    data["monitor"] = monitor(forward_rows(count=100, start=390))
    data["score_models"][0]["acceptance"] = {}
    assert (
        score(data, cfg)["score"] == 60
    )  # old model without its acceptance certificate


def test_mature_direction_moves_score_up_or_down_without_stacking_reads():
    up, cfg = mature()
    positive = score(up, cfg)
    assert 60 < positive["score"] < 74
    assert positive["research_overlay"]["families"][0]["status"] == "MATURE"
    assert positive["probability"] is False
    again = blend(positive, up, cfg, PAIR, NOW, NOW)
    assert again == positive  # never re-add last round's research contribution
    down, _ = mature(side="down")
    negative = score(down, cfg)
    assert 46 < negative["score"] < 60
    assert score(up, cfg, base=99)["score"] == 100
    assert score(down, cfg, base=1)["score"] == 0
    assert score(up, cfg, base=99)["research_adjustment"] == 1


@pytest.mark.parametrize(
    "problem",
    [
        "drift",
        "calibration",
        "negative_forward",
        "old_forecast",
        "old_research",
        "wrong_version",
        "wrong_design",
        "model_failure",
        "old_forward",
    ],
)
def test_maturity_is_withdrawn_when_quality_or_version_or_freshness_fails(problem):
    data, cfg = mature()
    if problem == "drift":
        data["monitor"]["drift"] = True
    if problem == "calibration":
        data["monitor"]["model_streams"][0]["calibration"]["ece"] = 0.3
    if problem == "negative_forward":
        data["monitor"]["model_streams"][0]["gain_lower_bound"] = -0.01
    if problem == "old_forecast":
        data["forecasts"][0]["bar_time"] -= HOUR
    if problem == "old_research":
        data["trained_at"] -= 49 * HOUR
    if problem == "wrong_version":
        data["forecasts"][0]["model_version"] = "new-v2"
    if problem == "wrong_design":
        data["score_models"][0]["acceptance"]["design"] = "other"
    if problem == "model_failure":
        data["stages"]["research"] = "FAILED"
    if problem == "old_forward":
        data["monitor"]["model_streams"][0]["last_settled_at"] = NOW - 80 * HOUR
    assert score(data, cfg)["score"] == 60


def test_forward_maturity_is_specific_to_model_version_and_not_baseline_or_old_models():
    data, cfg = mature()
    rows = (
        forward_rows(count=150, start=220)
        + forward_rows("new-v2", 30, start=370)
        + forward_rows("baseline", 200, start=100)
    )
    data["monitor"] = monitor(rows)
    cert = data["score_models"][0]
    cert["model_version"] = cert["acceptance"]["model_version"] = "new-v2"
    data["forecasts"][0]["model_version"] = "new-v2"
    stats = data["monitor"]["model_streams"]
    assert {r["model_version"] for r in stats} == {"v1", "new-v2"}
    assert next(r for r in stats if r["model_version"] == "new-v2")["effective_n"] <= 30
    assert score(data, cfg)["score"] == 60


def test_entry_only_uses_sufficient_clear_test_evidence_and_the_same_current_plan():
    data, cfg = mature()
    data["forecasts"] = []
    bars = make_bars(720, start=0)
    report = entry_research(bars, cfg, PAIR, 1, NOW)
    required = report["required_samples"]
    report.update(status="HISTORICAL_SUPPORT", effective_samples=2 * required)
    report["test"]["net"].update(effective_n=2 * required, interval=[0.01, 0.02])
    report["test"]["gain"].update(effective_n=2 * required, interval=[0.003, 0.004])
    data["entry_research"] = report
    data["trade_plan"] = {
        "status": "READY",
        "entry_policy_id": report["policy_id"],
        "entry_research": report,
    }
    assert 60 < score(data, cfg)["score"] <= 63
    report["test"]["gain"]["effective_n"] = required - 1
    pending = score(data, cfg)
    assert pending["score"] == 60
    assert pending["research_overlay"]["families"][1]["effective_samples"] == required - 1
    report["test"]["gain"]["effective_n"] = 2 * required
    report["test"]["gain"]["interval"] = [-0.003, 0.004]
    assert score(data, cfg)["score"] == 60
    report["test"]["gain"]["interval"] = [0.003, 0.004]
    data["trade_plan"]["status"] = "STALE"
    assert score(data, cfg)["score"] == 60
    data["trade_plan"]["status"] = "READY"
    report["status"] = "NOT_SUPPORTED"
    report["test"]["gain"]["interval"] = [-0.004, -0.003]
    report["test"]["net"]["interval"] = [-0.02, -0.01]
    assert 57 <= score(data, cfg)["score"] < 60


def test_stop_requires_matching_regime_policy_design_and_forward_evidence():
    data, cfg = mature()
    data["forecasts"] = []
    key = {"horizon_hours": 48, "regime": "quiet/normal", "policy_id": "atr:1.5"}
    historical = {
        **key,
        "status": "HISTORICAL_SUPPORT",
        "required_samples": 200,
        "paired_gain": {"effective_n": 400, "interval": [0.001, 0.003]},
    }
    prospective = {
        **key,
        "source": "Binance TR",
        "design_id": stop_design(cfg),
        "caution": False,
        "last_settled_at": NOW - HOUR,
        "paired_gain": {"effective_n": 150, "interval": [0.001, 0.002]},
    }
    data["stop_loss"] = {
        "trained_at": NOW,
        "research": {"design_id": stop_design(cfg), "candidates": [historical]},
        "suggestions": [{**key, "status": "HISTORICAL_SUPPORT"}],
        "forward": {"recent": [prospective], "data_gaps": 0},
    }
    assert 60 < score(data, cfg)["score"] <= 63
    prospective["regime"] = "volatile/high"
    assert score(data, cfg)["score"] == 60
    prospective["regime"] = key["regime"]
    prospective["paired_gain"]["effective_n"] = 20
    assert score(data, cfg)["score"] == 60
    prospective["paired_gain"]["effective_n"] = 150
    prospective["caution"] = True
    assert score(data, cfg)["score"] == 60
    prospective["caution"] = False
    data["stop_loss"]["forward"]["data_gaps"] = 1
    assert score(data, cfg)["score"] == 60


def test_missing_future_stale_failed_market_and_other_coin_never_gain_research_points():
    data, cfg = mature()
    for changed in [
        None,
        {**data, "pair": "ETHTRY"},
        {**data, "observed_at": 499 * HOUR},
        {**data, "backfill_complete": False},
        {**data, "source": "SYNTHETIC_DEMO"},
    ]:
        assert score(changed, cfg)["score"] == 60
    assert score(data, cfg, error="network failed")["score"] == 60
    assert blend({"score": 60}, data, cfg, PAIR, NOW, NOW + 16 * 60_000)["score"] == 60
    assert blend({"score": 60}, data, cfg, PAIR, NOW + 1, NOW)["score"] == 60
    assert blend({"score": None}, data, cfg, PAIR, NOW, NOW) == {"score": None}
    assert score(data, replace(cfg, pairs=(PAIR, "ETHTRY")))["score"] == 60


def test_phone_scan_read_restart_and_journal_use_hybrid_then_withdraw_on_drift(
    tmp_path, monkeypatch
):
    data, cfg = mature()
    now = [NOW]
    store = phone.PhoneStore(tmp_path)
    bars = make_bars(120, start=380 * HOUR)

    def publish(payload, job):
        token = store.acquire(PAIR, job, now[0])
        state = {
            "bars": bars,
            "completed_at": now[0],
            "backfill_complete": True,
            "stages": {},
            "trained_at": NOW,
        }
        store.commit(PAIR, token, state, payload, now[0])

    publish(data, "mature")
    store.write_metadata("PHONE-MARKETS", {"pairs": [PAIR], "discovered_at": NOW})

    class Quick:
        def tickers(self, at):
            return {PAIR: {"price": 100, "observed_at": at}}

        def rally(self, p, change):
            return {
                "pair": p,
                "as_of": now[0],
                "analysis": {"score": 60},
                "chart": {"hour": bars},
            }

    monkeypatch.setattr(phone, "PhoneMarket", Quick)
    monkeypatch.setattr(phone, "clock_ms", lambda: now[0])
    monkeypatch.setattr(
        phone,
        "run_pair",
        lambda *a, **k: pytest.fail("Score scan must not train research"),
    )
    phone.cycle(str(tmp_path), "[]", score_only=True)

    def read():
        return json.loads(phone.request(str(tmp_path), "[]", "GET", "/v2/snapshot"))[
            "body"
        ]["items"][0]

    first = read()
    assert first["rally_score"] > 60 and first["rally"]["analysis"]["base_score"] == 60
    assert read()["rally_score"] == first["rally_score"]
    assert (
        phone.PhoneStore(tmp_path).metadata("RALLY-" + PAIR)["analysis"]["score"]
        == first["rally_score"]
    )
    with sqlite3.connect(tmp_path / "research.sqlite3") as db:
        record = json.loads(
            db.execute("SELECT payload FROM records WHERE kind='rally'").fetchone()[0]
        )
        assert record["analysis"]["research_overlay"]["status"] == "ACTIVE"
    paused = copy.deepcopy(data)
    paused["monitor"]["drift"] = True
    now[0] += 30_000
    publish(paused, "withdraw")
    second = read()
    assert (
        second["rally_score"] == 60
        and second["rally"]["analysis"]["research_overlay"]["status"] == "PAUSED"
    )
    # Withdrawal uses cached market data; it does not fake a new exchange scan.
    assert second["rally"]["as_of"] == first["rally"]["as_of"]


def test_retained_model_can_earn_fresh_certificate_without_beating_itself(monkeypatch):
    import numpy as np
    import radar.research as module
    from radar.features import NAMES

    rng = np.random.default_rng(52)
    x = rng.normal(size=(1200, len(NAMES)))
    y = (x[:, 0] > 0).astype(int)
    times = np.arange(1200) * HOUR
    data = {
        "x": x,
        "y": np.column_stack([y, 1 - y]),
        "time": times,
        "end": times + HOUR,
    }
    monkeypatch.setattr(module, "dataset", lambda *a: data)

    def template(sign=1):
        return {
            "mean": [0.0] * len(NAMES),
            "scale": [1.0] * len(NAMES),
            "weights": [0.0, 10.0 * sign] + [0.0] * (len(NAMES) - 1),
            "interactions": [],
            "penalty": 1.0,
            "training_end": -1,
        }

    def fitted(features, labels, stamps, interactions, penalty):
        sign = 1 if np.corrcoef(features[:, 0], labels)[0, 1] > 0 else -1
        return {**template(sign), "training_end": int(stamps[-1])}

    monkeypatch.setattr(module, "fit", fitted)
    old = {
        **template(),
        "version": "installed-before-certificates",
        "baseline": 0.5,
        "horizon_hours": 1,
        "direction": "up",
    }
    cfg = Config(pairs=(PAIR,))
    report = module.research([], [], [], cfg, 1, {"1:up": old})
    retained = report["models"]["1:up"]
    assert retained["version"] == old["version"]
    assert retained["score_acceptance"]["gain_lower_bound"] > 0
    assert retained["score_acceptance"]["effective_test_samples"] >= 97
    assert retained["score_acceptance"]["model_version"] == old["version"]
    assert old.get("score_acceptance") is None  # no mutation of archived model
    assert (
        next(
            r
            for r in report["evaluations"]
            if r["horizon_hours"] == 1 and r["direction"] == "up"
        )["status"]
        == "RETAINED"
    )
