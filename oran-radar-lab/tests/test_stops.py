import copy
import json

import pytest

from radar.api import API, audit
from radar.config import Config, HOUR
from radar.paper import apply, new_position, track_pair
from radar.pipeline import run_pair
from radar.stops import (
    accumulate,
    context_at,
    design,
    forward_monitor,
    issue_session,
    policies,
    research_stops,
    required_samples,
    settle_sessions,
    simulate,
    update_stops,
)
from radar.storage import LocalStore
from conftest import FakeMarket, make_bars


def bar(time, op=100, high=101, low=99, close=100):
    return {"time": time, "open": op, "high": high, "low": low, "close": close}


def stop_session(start=10 * HOUR):
    return {
        "id": "one",
        "issued_at": start,
        "start_bar": start,
        "deadline_bar": start + HOUR,
        "horizon_hours": 2,
        "regime": "quiet/normal",
        "design_id": "test",
        "source": "TEST_FIXTURE",
        "policies": [{"id": "pct:0.02", "distance": 0.02}],
        "target_fraction": 0.03,
        "fee_bps": 28,
        "slippage_bps": 5,
        "entry": None,
        "trials": {},
        "mae": 0,
        "mfe": 0,
        "observed_through": None,
        "status": "PENDING",
        "aggregated": False,
    }


def position(now=100):
    return new_position(
        {
            "position_id": "47ce5261-3251-4b17-85f5-d64c32c058f9",
            "pair": "BTCTRY",
            "entry": 100,
            "stop": 98,
            "target": 103,
        },
        now,
        ("BTCTRY",),
    )


def test_stop_context_and_levels_cannot_use_future():
    bars = make_bars(300)
    changed = copy.deepcopy(bars)
    for b in changed[200:]:
        b.update(high=10000, low=1, close=5000, quote_volume=1e12)
    assert context_at(bars, 199) == context_at(changed, 199)
    assert context_at(bars, 200) != context_at(changed, 200)


def test_power_count_uses_training_variance_effect_size_and_multiplicity():
    cfg = Config(pairs=("BTCTRY",))
    n = required_samples(0.01, cfg, 0.01)
    assert n > 10
    assert required_samples(0.02, cfg, 0.01) >= 4 * n - 4
    assert required_samples(0.01, cfg, 0.0001) > n
    assert required_samples(0.01, Config(stop_min_gain_bps=5), 0.01) > 3 * n
    assert design(cfg) != design(Config(pairs=("BTCTRY",), stop_slippage_bps=10))


def test_both_barriers_in_one_candle_stay_ambiguous_and_have_return_bounds():
    result = simulate(
        [bar(0, high=104, low=97)],
        100,
        [{"id": "pct:0.02", "distance": 0.02}],
        0.03,
        Config(),
    )
    assert result["pct:0.02"]["outcome"] == "AMBIGUOUS"
    assert result["pct:0.02"]["net_lower"] < 0 < result["pct:0.02"]["net_upper"]
    assert result["no-stop"]["outcome"] == "TARGET_HIT"


def test_gap_through_stop_uses_worse_open_not_nominal_stop_fill():
    result = simulate(
        [bar(0, op=96, high=97, low=95, close=96)],
        100,
        [{"id": "pct:0.02", "distance": 0.02}],
        0.03,
        Config(),
    )
    trial = result["pct:0.02"]
    assert trial["outcome"] == "STOPPED"
    assert trial["net_upper"] < -0.04


def test_stop_recovery_does_not_rewrite_stop_outcome():
    rows = [
        bar(0, high=101, low=97, close=98),
        bar(HOUR, op=98, high=104, low=98, close=103),
    ]
    result = simulate(rows, 100, [{"id": "pct:0.02", "distance": 0.02}], 0.03, Config())
    trial = result["pct:0.02"]
    assert trial["outcome"] == "STOPPED" and trial["recovered_after_stop"]
    assert trial["net_lower"] < 0
    assert trial["exit_bar"] == 0


def test_prospective_entry_waits_for_first_full_hour_after_issuance():
    cfg = Config(pairs=("BTCTRY",), stop_horizons=(6,))
    bars = make_bars(799)  # UTC aligned last anchor.
    context = context_at(bars, len(bars) - 1)
    report = {
        "round": 1,
        "design_id": design(cfg),
        "candidates": [],
        "cutoffs": {"6": context},
    }
    closed = bars[-1]["time"] + HOUR
    s = issue_session("BTCTRY", bars, report, cfg, 6, closed + 300000, "TEST_FIXTURE")
    assert s["start_bar"] == closed + HOUR and s["entry"] is None
    exact = issue_session("BTCTRY", bars, report, cfg, 6, closed, "TEST_FIXTURE")
    assert exact["start_bar"] == s["start_bar"]
    assert (
        issue_session("BTCTRY", bars, report, cfg, 6, closed - 1, "TEST_FIXTURE")
        is None
    )
    assert (
        issue_session("BTCTRY", bars, report, cfg, 6, closed + 600001, "TEST_FIXTURE")
        is None
    )
    before = copy.deepcopy(s)
    settle_sessions([s], [bar(closed, high=10000, low=1)])
    assert s == before


def test_shadow_gap_blocks_settlement_and_resumes_without_rewriting_exit():
    s = stop_session()
    start = s["start_bar"]
    settle_sessions([s], [bar(start + HOUR, high=104)])
    assert s["status"] == "DATA_GAP" and not s["trials"]
    settle_sessions([s], [bar(start, low=97, close=98)])
    assert s["status"] == "PENDING" and s["trials"]["pct:0.02"]["outcome"] == "STOPPED"
    frozen = s["trials"]["pct:0.02"]["net_lower"]
    settle_sessions([s], [bar(start + HOUR, op=98, high=104, low=98, close=103)])
    assert s["status"] == "SETTLED" and s["trials"]["pct:0.02"]["net_lower"] == frozen
    assert s["trials"]["pct:0.02"]["recovered_after_stop"]


def test_lifetime_aggregation_is_idempotent_and_survives_raw_retention():
    s = stop_session()
    start = s["start_bar"]
    settle_sessions(
        [s],
        [
            bar(start, low=97, close=98),
            bar(start + HOUR, op=98, high=104, low=98, close=103),
        ],
    )
    lifetime = accumulate([s], {})
    before = copy.deepcopy(lifetime)
    accumulate([s], lifetime)
    assert lifetime == before
    value = next(iter(lifetime.values()))
    assert value["n"] == 1 and value["recovered_after_stop"] == 1
    assert forward_monitor([], lifetime)["lifetime"][0]["n"] == 1


def test_pruning_closed_sessions_preserves_lifetime_counts():
    first = stop_session()
    start = first["start_bar"]
    settle_sessions(
        [first],
        [
            bar(start, low=97, close=98),
            bar(start + HOUR, op=98, high=104, low=98, close=103),
        ],
    )
    second = copy.deepcopy(first)
    second.update(id="two", issued_at=start + HOUR)
    state = {"source": "TEST_FIXTURE", "stop_sessions": [first, second]}
    bars = make_bars(150)
    cfg = Config(pairs=("BTCTRY",), stop_horizons=(6,), max_forward=1)
    update_stops(state, bars, cfg, "BTCTRY", bars[-1]["time"] + 2 * HOUR)
    assert len(state["stop_sessions"]) == 1
    assert next(iter(state["stop_lifetime"].values()))["n"] == 2
    update_stops(state, bars, cfg, "BTCTRY", bars[-1]["time"] + 2 * HOUR)
    assert next(iter(state["stop_lifetime"].values()))["n"] == 2


def test_learnable_loss_reduction_can_pass_evidence_without_claiming_profit():
    count = 12000
    bars = []
    price = 100.0
    for i in range(count):
        op, price = price, price * 0.98
        bars.append(
            {
                **bar(
                    1704067200000 + i * HOUR,
                    op=op,
                    high=op * 1.001,
                    low=price * 0.999,
                    close=price,
                ),
                "quote_volume": 1000,
            }
        )
    cfg = Config(
        pairs=("BTCTRY",),
        stop_horizons=(6,),
        stop_percentages=(0.02,),
        stop_atr_multipliers=(1.0,),
    )
    report = research_stops(bars, cfg, 1)
    supported = [r for r in report["candidates"] if r["status"] == "HISTORICAL_SUPPORT"]
    assert supported
    assert all(r["policy_id"] == "pct:0.02" for r in supported)
    assert all(r["paired_gain"]["interval"][0] > 0 for r in supported)
    assert all(not r["positive_net_expectation"] for r in supported)


def test_verified_empty_tail_completes_post_stop_followup_at_deadline(tmp_path):
    store = LocalStore(tmp_path)
    p = position()
    apply(p, {"id": 10, "time": 101, "price": 97})
    store.create_position(p)
    assert track_pair(store, FakeMarket([]), "BTCTRY", p["deadline_at"]) == "OK"
    saved = store.positions()[0]
    assert saved["followup_state"] == "COMPLETED" and saved["recovered_at"] is None
    assert saved["net_return_pct"] == p["net_return_pct"]


def test_stop_research_splits_are_purged_and_context_cutoffs_train_only():
    bars = make_bars(1800)
    cfg = Config(pairs=("BTCTRY",), stop_horizons=(6,))
    original = research_stops(bars, cfg, 1)
    changed = copy.deepcopy(bars)
    for b in changed[1500:]:
        b["high"] *= 2
        b["quote_volume"] *= 100
    altered = research_stops(changed, cfg, 1)
    assert original["cutoffs"] == altered["cutoffs"]
    assert original["family_tests"] == len(policies(cfg)) * 4
    assert original["evaluations"]
    for r in original["evaluations"]:
        assert (
            r["split"]["training_end"]
            < r["split"]["validation_start"]
            < r["split"]["test_start"]
        )
        assert r["status"] != "HISTORICAL_SUPPORT"
        assert r["required_samples"] >= 97
    assert {r["policy_id"] for r in original["candidates"]} == {
        r["policy_id"] for r in altered["candidates"]
    }


def test_stop_plans_are_immutable_retries_and_config_changes_do_not_reissue():
    bars = make_bars(799)
    now = bars[-1]["time"] + HOUR
    cfg = Config(pairs=("BTCTRY",), stop_horizons=(6,))
    state = {"source": "TEST_FIXTURE"}
    update_stops(state, bars, cfg, "BTCTRY", now, train=True)
    first = copy.deepcopy(state["stop_sessions"])
    assert first
    update_stops(state, bars, cfg, "BTCTRY", now + 300000)
    assert state["stop_sessions"] == first and state["stop_research"]["round"] == 1
    update_stops(
        state,
        bars,
        Config(pairs=("BTCTRY",), stop_horizons=(6,), stop_slippage_bps=12),
        "BTCTRY",
        now + 300000,
    )
    assert state["stop_sessions"] == first and state["stop_research"]["round"] == 2


def test_actual_trade_followup_preserves_realized_loss_and_target_timestamp():
    p = position()
    apply(p, {"id": 1, "time": 101, "price": 97})
    frozen = (p["net_return_pct"], p["closed_at"], p["exit_price"])
    apply(p, {"id": 2, "time": 102, "price": 99})
    assert p["post_stop_mfe_pct"] < 0
    apply(p, {"id": 3, "time": 103, "price": 104})
    assert p["state"] == "STOPPED" and p["followup_state"] == "RECOVERED_TO_TARGET"
    assert p["target_hit_at"] is None and p["recovered_at"] == 103
    assert (p["net_return_pct"], p["closed_at"], p["exit_price"]) == frozen


def test_recovery_after_deadline_does_not_count_as_recovered():
    p = position()
    apply(p, {"id": 1, "time": 101, "price": 97})
    apply(p, {"id": 2, "time": p["deadline_at"], "price": 104})
    assert p["followup_state"] == "COMPLETED" and p["recovered_at"] is None


def test_followup_trade_gap_cannot_invent_recovery(tmp_path):
    store = LocalStore(tmp_path)
    p = position()
    apply(p, {"id": 10, "time": 101, "price": 97})
    store.create_position(p)
    market = FakeMarket([])
    market.trade_batches = [[{"a": 12, "T": 102, "p": 104}]]
    assert track_pair(store, market, "BTCTRY", 103) == "BACKFILLING"
    assert store.positions()[0]["recovered_at"] is None
    market.trade_batches = [[{"a": 11, "T": 102, "p": 104}]]
    assert track_pair(store, market, "BTCTRY", 103) == "OK"
    assert store.positions()[0]["followup_state"] == "RECOVERED_TO_TARGET"


def test_api_snapshot_stop_routes_and_audit_are_integrated(tmp_path):
    bars = make_bars(799)
    now = bars[-1]["time"] + HOUR
    cfg = Config(pairs=("BTCTRY",), token="private-test")
    store = LocalStore(tmp_path)
    run_pair(
        store, FakeMarket(bars), cfg, "BTCTRY", "stop-test", now, clock=lambda: now
    )
    store.heartbeat(now)
    api = API(store, cfg, lambda *args: None)
    headers = {"Authorization": "Bearer private-test"}
    assert (
        api.handle("POST", "/v2/stop-loss", "pair=BTCTRY", headers, "{}", now)[0] == 405
    )
    assert api.handle("GET", "/v2/stop-loss", "pair=BTCTRY", {}, "", now)[0] == 401
    for path in ("/v2/stop-loss", "/v2/stop-loss/research", "/v2/stop-loss/forward"):
        code, data = api.handle("GET", path, "pair=BTCTRY", headers, "", now)
        assert code == 200
        json.dumps(data, allow_nan=False)
    assert (
        api.handle(
            "GET", "/v2/stop-loss/forward", "pair=BTCTRY&offset=bad", headers, "", now
        )[0]
        == 400
    )
    snapshot = api.handle("GET", "/v2/snapshot", "", headers, "", now)[1]
    assert "stop_loss" in snapshot["items"][0]
    assert store.metadata("BTCTRY")["stop_trained_at"] == now
    assert audit(store, cfg, now)["ok"]
    body = {
        "position_id": position()["position_id"],
        "pair": "BTCTRY",
        "entry": 100,
        "target": 103,
        "stop": 98,
        "market_context": {"source": "fabricated"},
    }
    status, saved = api.handle(
        "POST", "/paper-positions", "", headers, json.dumps(body), now
    )
    assert status == 201 and saved["market_context"]["source"] == "TEST_FIXTURE"
    assert saved["market_context"]["stop_context"]["atr_fraction"] > 0
    assert "volume_ratio@lag0" in saved["market_context"]["features"]
    captured = copy.deepcopy(saved["market_context"])
    public = store.read("BTCTRY", public=True)
    public["observed_at"] = now + HOUR
    token = store.acquire("BTCTRY", "future-fixture", now + 1)
    store.commit("BTCTRY", token, store.read("BTCTRY"), public, now + 2)
    replay = api.handle(
        "POST", "/paper-positions", "", headers, json.dumps(body), now + 3
    )[1]
    assert replay["market_context"] == captured
    body["position_id"] = "47ce5261-3251-4b17-85f5-d64c32c058fa"
    future = api.handle(
        "POST", "/paper-positions", "", headers, json.dumps(body), now + 3
    )[1]
    assert future["market_context"]["stale"]
    assert (
        future["market_context"]["features"]
        == future["market_context"]["stop_context"]
        == {}
    )


@pytest.mark.parametrize(
    "kwargs",
    [
        {"stop_slippage_bps": float("nan")},
        {"stop_percentages": (0.5,)},
        {"stop_horizons": (0,)},
        {"stop_horizons": (6, 6)},
        {"stop_min_gain_bps": 0},
    ],
)
def test_invalid_stop_protocol_is_rejected(kwargs):
    with pytest.raises(ValueError):
        Config(**kwargs)


def test_opening_stop_gap_then_target_high_has_known_recovery_order():
    result = simulate(
        [bar(0, op=96, high=104, low=95, close=103)],
        100,
        [{"id": "pct:0.02", "distance": 0.02}],
        0.03,
        Config(),
    )
    trial = result["pct:0.02"]
    assert trial["outcome"] == "STOPPED" and trial["recovered_after_stop"]
    assert trial["net_upper"] < 0


def test_long_research_cannot_backdate_a_shadow_plan():
    bars = make_bars(799)
    closed = bars[-1]["time"] + HOUR
    cfg = Config(pairs=("BTCTRY",), stop_horizons=(6,))
    state = {"source": "TEST_FIXTURE"}
    update_stops(
        state, bars, cfg, "BTCTRY", closed, train=True, clock=lambda: closed + 600001
    )
    assert state["stop_sessions"] == []
    assert state["stop_trained_at"] == closed + 600001
