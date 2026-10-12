import copy
import json
import sqlite3
import zipfile
from dataclasses import replace

import pytest

from conftest import make_bars, FakeMarket
from radar import entries, phone
from radar.config import Config, HOUR
from radar.pipeline import run_pair
from radar.plans import build_plan, rebase, current_plan


def candles(low=99, high=101, close=100, op=100):
    return [
        {"time": i * HOUR, "open": op, "high": high, "low": low, "close": close}
        for i in range(54)
    ]


def test_unfilled_limits_are_zero_per_decision_and_market_pays_costs():
    cfg = Config()
    missed = entries.simulate(candles(), 98, 0.03, 0.05, cfg)
    assert missed["outcome"] == "UNFILLED" and missed["net_lower"] == 0
    market = entries.simulate(candles(), None, 0.03, 0.05, cfg, market=True)
    assert market["outcome"] == "TIMEOUT" and market["net_lower"] < 0
    result = entries.metrics([{"trials": {"p": missed}, "baseline": market}], "p", 0.05)
    assert result["n"] == 1 and result["filled"] == 0
    assert result["net"]["mean"] == 0 and result["gain"]["mean"] > 0


def test_fill_bar_high_before_limit_does_not_invent_guaranteed_target():
    window = candles(low=98, high=100, close=99, op=99)
    window[0].update(open=100, high=104, low=98, close=99)
    trial = entries.simulate(window, 99, 0.05, 0.03, Config())
    assert trial["outcome"] == "AMBIGUOUS"
    assert trial["net_lower"] < 0 < trial["net_upper"]
    # If the fill occurs at the open, all later extrema are after entry.
    window[0]["open"] = 99
    trial = entries.simulate(window, 99, 0.05, 0.03, Config())
    assert trial["outcome"] == "TARGET_HIT"
    assert trial["net_lower"] == trial["net_upper"]


def test_gap_fill_improvement_stop_gap_and_expiry_are_respected():
    window = candles(low=96, high=99, close=98, op=98)
    trial = entries.simulate(window, 99, 0.05, 0.05, Config())
    assert trial["filled"] and trial["outcome"] == "TIMEOUT"
    assert trial["net_lower"] < 0  # filled at 98, not credited a purchase at 99
    window[1].update(open=90, low=89, high=91, close=90)
    trial = entries.simulate(window, 99, 0.05, 0.05, Config())
    assert trial["outcome"] == "STOPPED" and trial["net_lower"] < -0.08
    late = candles(low=99, high=101)
    late[6]["low"] = 97
    assert not entries.simulate(late, 98, 0.03, 0.05, Config())["filled"]


def periodic_pullbacks(count=5500):
    bars = [
        {
            "time": i * HOUR,
            "open": 100.0,
            "high": 100.2,
            "low": 99.8,
            "close": 100.0,
            "quote_volume": 1000.0,
        }
        for i in range(count)
    ]
    # A repeatable 2% pullback followed by recovery: costly to buy immediately,
    # profitable to wait; 3% orders miss the movement entirely.
    for i in range(55, count - 2, 55):
        bars[i + 1].update(open=100.0, high=100.2, low=98.0, close=98.0)
        bars[i + 2].update(open=98.0, high=100.2, low=98.0, close=100.0)
    return bars


def test_optimizer_prefers_filled_pullback_over_market_and_unfilled_deeper_limit():
    report = entries.research(periodic_pullbacks(), Config(), "BTCTRY", 1, 5500 * HOUR)
    assert report["policy_id"] == "pct:0.02"
    selected = report["test"]
    assert selected["fill_rate"] == 1
    assert selected["net"]["mean"] > 0 and selected["gain"]["mean"] > 0
    assert (
        next(c for c in report["candidates"] if c["policy_id"] == "pct:0.03")["test"][
            "filled"
        ]
        == 0
    )
    # Twenty repeated windows are not certified just because they are profitable.
    assert report["status"] == "UNDERPOWERED" and report["required_samples"] > 10


def test_test_partition_never_selects_the_entry_policy_and_gaps_are_excluded():
    bars = periodic_pullbacks()
    first = entries.research(bars, Config(), "BTCTRY", 1, 5500 * HOUR)
    cutoff = first["split_bounds"]["test_start"]
    altered = copy.deepcopy(bars)
    for b in altered:
        if b["time"] >= cutoff:
            b.update(open=100.0, low=99.9, high=100.1, close=100.0)
    second = entries.research(altered, Config(), "BTCTRY", 1, 5500 * HOUR)
    assert first["policy_id"] == second["policy_id"]
    assert first["test"]["net"]["mean"] != second["test"]["net"]["mean"]
    assert (
        first["split_bounds"]["train_end"] <= first["split_bounds"]["validation_start"]
    )
    assert (
        first["split_bounds"]["validation_end"] <= first["split_bounds"]["test_start"]
    )
    rows = entries.samples(bars, Config())
    assert all(a["end"] <= b["time"] for a, b in zip(rows, rows[1:]))
    assert len(entries.samples(bars[:1000] + bars[1001:], Config())) < len(rows)


def test_levels_use_only_closed_context_and_do_not_chase_ticker_or_crossed_entry():
    bars = periodic_pullbacks()
    cfg = Config()
    report = entries.research(bars, cfg, "BTCTRY", 1, 5500 * HOUR)
    now = bars[-1]["time"] + HOUR + 1
    plan = build_plan("BTCTRY", bars, 101, now, now, cfg, entry_research=report)
    assert plan["entry"] == pytest.approx(98)
    assert plan["entry_basis"] == "HISTORICAL_COMPARISON"
    quote = rebase(plan, 108, now + 1, now + 1)
    assert [quote[k] for k in ("id", "entry", "stop", "target")] == [
        plan[k] for k in ("id", "entry", "stop", "target")
    ]
    assert quote["reference_price"] == 108
    assert rebase(plan, 97, now, now)["status"] == "STALE"
    opened = {**bars[-1], "time": bars[-1]["time"] + HOUR, "low": 1}
    assert (
        build_plan(
            "BTCTRY", bars + [opened], 101, now, now, cfg, entry_research=report
        )["entry"]
        == 98
    )
    changed = copy.deepcopy(bars)
    changed[-1].update(open=100.0, high=111.0, low=99.8, close=110.0)
    assert build_plan("BTCTRY", changed, 111, now, now, cfg, entry_research=report)[
        "entry"
    ] == pytest.approx(107.8)
    legacy = {**plan, "method": "causal-entry-atr-v1"}
    assert current_plan(legacy, now)["status"] == "STALE"


def test_round_budget_power_protocol_and_charging_control_research():
    bars = periodic_pullbacks(1100)
    cfg, now = Config(), 1100 * HOUR
    state = {}
    assert (
        entries.update(state, bars, cfg, "BTCTRY", now, allow_training=False)
        == "WAITING_CHARGE"
    )
    assert "entry_research" not in state
    entries.update(state, bars, cfg, "BTCTRY", now)
    report = copy.deepcopy(state["entry_research"])
    entries.update(state, bars, cfg, "BTCTRY", now + 25 * HOUR)
    assert state["entry_research"] == report  # no new closed candles
    later = bars + [{**bars[-1], "time": bars[-1]["time"] + HOUR}]
    entries.update(state, later, cfg, "BTCTRY", now + 25 * HOUR)
    assert state["entry_research"]["round"] == 2
    assert state["entry_research"]["comparison_alpha"] < report["comparison_alpha"]
    tighter = entries.research(
        bars, replace(cfg, entry_min_gain_bps=5), "BTCTRY", 1, now
    )
    assert tighter["required_samples"] >= report["required_samples"]
    assert not entries.compatible(report, replace(cfg, fee_bps=40), now)
    assert not entries.compatible(report, replace(cfg, pairs=("BTCTRY", "ETHTRY")), now)
    assert not entries.compatible(report, cfg, now - 1)


def test_deep_report_restart_journal_and_quick_job_use_same_frozen_policy(
    tmp_path, monkeypatch
):
    bars = make_bars(720)
    now = bars[-1]["time"] + HOUR + 1
    store = phone.PhoneStore(tmp_path)
    pair, cfg = "BTCTRY", Config(pairs=("BTCTRY",))
    run_pair(
        store, FakeMarket(bars), cfg, pair, "entry-research", now=now, clock=lambda: now
    )
    frozen = store.read(pair, public=True)["entry_research"]
    assert frozen["round"] == 1
    with sqlite3.connect(tmp_path / "research.sqlite3") as db:
        saved = db.execute(
            "SELECT payload FROM records WHERE kind='entry_research'"
        ).fetchall()
        assert len(saved) == 1 and json.loads(saved[0][0]) == frozen
    store.write_metadata(
        "PHONE-MARKETS",
        {
            "pairs": [pair],
            "tickers": {pair: {"price": bars[-1]["close"] * 1.05, "observed_at": now}},
        },
    )

    class Quick:
        def tickers(self, at):
            return store.metadata("PHONE-MARKETS")["tickers"]

        def rally(self, p, btc):
            return {
                "pair": p,
                "as_of": now,
                "analysis": {"score": 77},
                "chart": {"hour": bars[-120:]},
            }

    monkeypatch.setattr(phone, "PhoneMarket", Quick)
    monkeypatch.setattr(phone, "clock_ms", lambda: now)
    monkeypatch.setattr(
        entries,
        "research",
        lambda *a: pytest.fail("quick score must not run historical optimization"),
    )
    phone.cycle(str(tmp_path), '["BTCTRY"]', score_only=True)
    restarted = phone.PhoneStore(tmp_path)
    plan = phone.market_item(restarted, pair, now)["trade_plan"]
    assert plan["entry_research"] == frozen
    assert plan["entry_policy_id"] == frozen["policy_id"]
    assert restarted.read(pair, public=True)["entry_research"] == frozen


def test_control_detects_missing_entry_research_and_backup_preserves_the_report(
    tmp_path,
):
    from radar.api import API, audit

    bars = make_bars(720)
    now, cfg = bars[-1]["time"] + HOUR + 1, Config(pairs=("BTCTRY",))
    store = phone.PhoneStore(tmp_path / "state")
    run_pair(
        store, FakeMarket(bars), cfg, "BTCTRY", "research", now=now, clock=lambda: now
    )
    store.heartbeat(now)
    assert audit(store, cfg, now)["ok"]
    report = store.read("BTCTRY", public=True)["entry_research"]
    archive = phone.backup(str(store.root), '["BTCTRY"]')
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        db_copy = tmp_path / "backup.sqlite3"
        db_copy.write_bytes(z.read("research.sqlite3"))
        with sqlite3.connect(db_copy) as db:
            assert (
                json.loads(
                    db.execute(
                        "SELECT payload FROM records WHERE kind='entry_research'"
                    ).fetchone()[0]
                )
                == report
            )
    meta = store.metadata("BTCTRY")
    meta.pop("entry_trained_at")
    store.write_metadata("BTCTRY", meta)
    assert {i["code"] for i in audit(store, cfg, now)["issues"]} == {
        "ENTRY_RESEARCH_MISSING"
    }
    queued = []
    api = API(store, cfg, lambda *args: queued.append(args))
    status, result = api.handle("POST", "/v2/control/reconcile", {}, {}, "", now)
    assert status == 202 and result["status"] == "RECOVERY_QUEUED"
    assert queued[0][0] == "BTCTRY"
    # Queueing recovery keeps the last successful report, never invents success.
    assert store.read("BTCTRY", public=True)["entry_research"] == report
