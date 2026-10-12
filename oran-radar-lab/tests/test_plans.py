import copy
import json
import sqlite3
import pytest

from conftest import make_bars, FakeMarket
from radar import phone
from radar.config import Config, HOUR, PERIOD
from radar.pipeline import run_pair
from radar.plans import build_plan, current_plan, rebase


def fixture(count=120):
    bars = make_bars(count)
    now = bars[-1]["time"] + HOUR + 1000
    return bars, now, Config(pairs=("BTCTRY",))


def test_short_scan_produces_entry_target_and_atr_stop_without_model_training():
    bars, now, cfg = fixture()
    price = bars[-1]["close"]
    plan = build_plan("BTCTRY", bars, price, now, now, cfg)
    assert plan["status"] == "READY"
    assert 0 < plan["stop"] < plan["entry"] < price < plan["target"]
    assert plan["stop_basis"] == "ATR_BASELINE"
    assert plan["automatic_apply"] is False
    assert plan["plan_profitability_validated"] is False
    assert (
        plan["entry_probability"] is None and plan["evidence_status"] == "UNDERPOWERED"
    )
    assert plan["required_samples"] > plan["effective_samples"]
    assert plan["entry_policy_id"] == "atr:1"
    assert plan["entry_basis"] == "ATR_PRELIMINARY"
    assert plan["anchor_price"] == price
    # A live quote update changes the quote, not the researched prices.
    refreshed = rebase(plan, price * 1.1, now, now)
    assert [refreshed[k] for k in ("id", "entry", "stop", "target")] == [
        plan[k] for k in ("id", "entry", "stop", "target")
    ]


def test_plan_ignores_open_and_future_bars():
    bars, now, cfg = fixture()
    plan = build_plan("BTCTRY", bars, 100, now, now, cfg)
    future = copy.deepcopy(bars[-1])
    future["time"] += HOUR
    future.update(close=100000, high=100001, low=1, open=1)
    assert build_plan("BTCTRY", bars + [future], 100, now, now, cfg) == plan
    assert plan["closed_at"] == bars[-1]["time"] + HOUR


@pytest.mark.parametrize("status", ["UNDERPOWERED", "NOT_SUPPORTED", "FORWARD_CAUTION"])
def test_unsupported_stop_research_does_not_impersonate_supported_distance(status):
    bars, now, cfg = fixture()
    candidates = {
        "suggestions": [
            {
                "horizon_hours": 48,
                "status": status,
                "policy_id": "percent:0.01",
                "distance_pct": 1,
            }
        ]
    }
    plan = build_plan("BTCTRY", bars, 100, now, now, cfg, candidates)
    assert plan["stop_basis"] == "ATR_BASELINE"
    candidates["suggestions"][0]["status"] = "HISTORICAL_SUPPORT"
    supported = build_plan("BTCTRY", bars, 100, now, now, cfg, candidates)
    assert supported["stop_basis"] == "RESEARCH_DISTANCE_ADAPTED"
    assert supported["stop_fraction"] == 0.01
    assert supported["stop"] == pytest.approx(supported["entry"] * 0.99)
    assert supported["plan_profitability_validated"] is False


def test_stale_future_invalid_quotes_and_missing_history_do_not_produce_current_plan():
    bars, now, cfg = fixture()
    plan = build_plan("BTCTRY", bars, 100, now, now, cfg)
    assert current_plan(plan, now + 4 * PERIOD)["status"] == "STALE"
    assert rebase(plan, 100, now + 1, now)["status"] == "STALE"
    assert rebase(plan, 110, now, now)["entry"] == plan["entry"]
    assert build_plan("BTCTRY", bars[:15], 100, now, now, cfg)["entry"] is None
    assert (
        build_plan("BTCTRY", bars, float("nan"), now, now, cfg)["status"] == "WAITING"
    )
    assert (
        build_plan("BTCTRY", bars[:50] + bars[51:], 100, now, now, cfg)["entry"] is None
    )
    assert (
        build_plan("BTCTRY", bars, 100, now, now, cfg, spread_pct=100)["entry"] is None
    )
    # No mutation of the archived original while marking an old read stale.
    assert plan["status"] == "READY"


def test_phone_exposes_quick_plan_and_saves_each_coin_separately_with_immutable_context(
    tmp_path, monkeypatch
):
    bars, now, cfg = fixture()
    monkeypatch.setattr(phone, "clock_ms", lambda: now)
    store = phone.PhoneStore(tmp_path)
    pairs = ["BTCTRY", "ETHTRY"]
    store.write_metadata(
        "PHONE-MARKETS",
        {
            "pairs": pairs,
            "tickers": {p: {"price": 100, "observed_at": now} for p in pairs},
        },
    )
    for pair in pairs:
        store.save_rally(
            pair,
            {
                "pair": pair,
                "as_of": now,
                "analysis": {"score": 80},
                "trade_plan": build_plan(pair, bars, 100, now, now, cfg),
            },
        )
    snapshot = json.loads(phone.request(str(tmp_path), "[]", "GET", "/v2/snapshot"))[
        "body"
    ]
    assert all(item["trade_plan"]["stop"] > 0 for item in snapshot["items"])
    saved = []
    for pair, sid in zip(
        pairs,
        [
            "571dd4b0-2aa0-4586-b732-3fe2f3a37dd1",
            "04a113f3-33e3-4a79-8a9b-263b3776d178",
        ],
    ):
        plan = next(
            item["trade_plan"] for item in snapshot["items"] if item["pair"] == pair
        )
        body = json.dumps(
            {
                "position_id": sid,
                "pair": pair,
                "entry": plan["entry"],
                "target": plan["target"],
                "stop": plan["stop"],
            }
        )

        def post():
            return json.loads(
                phone.request(
                    str(tmp_path), "[]", "POST", "/paper-positions", body=body
                )
            )

        first = post()
        assert first["status"] == 201
        assert first["body"]["market_context"]["proposed_plan"]["id"] == plan["id"]
        assert first["body"]["deadline_at"] == now + 48 * HOUR
        store.save_rally(
            pair,
            {
                "pair": pair,
                "as_of": now + 1,
                "analysis": {"score": 90},
                "trade_plan": rebase(plan, 110, now + 1, now + 1),
            },
        )
        assert post()["body"] == first["body"]
        saved.append(first["body"])
    assert len(store.positions()) == 2
    assert {p["pair"] for p in store.positions()} == set(pairs)


def test_deep_pipeline_plan_and_ledger_survive_repoll_without_charging(tmp_path):
    bars, now, cfg = fixture(720)
    store = phone.PhoneStore(tmp_path)
    store.write_metadata(
        "PHONE-MARKETS", {"tickers": {"BTCTRY": {"price": 101, "observed_at": now}}}
    )
    for job in ["first", "repeat"]:
        run_pair(
            store,
            FakeMarket(bars),
            cfg,
            "BTCTRY",
            job,
            now=now,
            clock=lambda: now,
            allow_training=False,
        )
    plan = store.read("BTCTRY", public=True)["trade_plan"]
    assert plan["status"] == "READY" and plan["reference_price"] == 101
    assert store.read("BTCTRY")["trade_plan"] == plan
    with sqlite3.connect(tmp_path / "research.sqlite3") as db:
        assert (
            db.execute(
                "SELECT COUNT(*) FROM records WHERE kind='trade_plan'"
            ).fetchone()[0]
            == 1
        )


def test_background_quick_cycle_exposes_plans_before_deep_research_completes(
    tmp_path, monkeypatch
):
    bars, now, _ = fixture()
    pairs = ["BTCTRY", "ETHTRY"]

    class QuickMarket:
        def symbols(self):
            return pairs

        def tickers(self, at):
            return {pair: {"price": 100, "observed_at": at} for pair in pairs}

        def rally(self, pair, btc_change):
            return {
                "pair": pair,
                "as_of": now,
                "analysis": {"score": 80, "spread_pct": 0.1},
                "chart": {"hour": bars},
            }

    monkeypatch.setattr(phone, "clock_ms", lambda: now)
    monkeypatch.setattr(phone, "PhoneMarket", QuickMarket)
    # No successful deep snapshot or trained model exists in this first round.
    monkeypatch.setattr(phone, "run_pair", lambda *a, **k: {"status": "DEFERRED"})
    json.loads(phone.cycle(str(tmp_path), "[]", charging=False))
    snapshot = json.loads(phone.request(str(tmp_path), "[]", "GET", "/v2/snapshot"))[
        "body"
    ]
    assert {item["pair"] for item in snapshot["items"]} == set(pairs)
    assert all(item["rally_score"] == 80 for item in snapshot["items"])
    for item in snapshot["items"]:
        plan = item["trade_plan"]
        assert plan["status"] == "READY"
        assert 0 < plan["stop"] < plan["entry"] < plan["anchor_price"] < plan["target"]
        assert plan["reference_price"] == item["price"]
        assert plan["stop_basis"] == "ATR_BASELINE"
    store = phone.PhoneStore(tmp_path)
    failed_plan = build_plan("BTCTRY", bars[:5], 100, now, now, Config())
    store.save_rally("BTCTRY", {"as_of": now, "trade_plan": failed_plan})
    assert phone.market_item(store, "BTCTRY", now)["trade_plan"] == failed_plan


def test_pairs_market_and_snapshot_carry_the_same_entry_and_stop_for_purchase_forms(
    tmp_path, monkeypatch
):
    bars, now, cfg = fixture()
    store = phone.PhoneStore(tmp_path)
    pair = "BTCTRY"
    plan = build_plan(pair, bars, 100, now, now, cfg)
    store.write_metadata(
        "PHONE-MARKETS",
        {"pairs": [pair], "tickers": {pair: {"price": 100, "observed_at": now}}},
    )
    store.save_rally(
        pair,
        {"pair": pair, "as_of": now, "analysis": {"score": 70}, "trade_plan": plan},
    )
    monkeypatch.setattr(phone, "clock_ms", lambda: now)

    def get(path, query=""):
        return json.loads(phone.request(str(tmp_path), "[]", "GET", path, query=query))[
            "body"
        ]

    selected = get("/pairs")[0]
    detail = get("/market", "pair=BTCTRY")["research"]
    card = get("/v2/snapshot")["items"][0]
    for item in (selected, detail, card):
        assert item["trade_plan"]["entry"] != item["price"]
        assert (
            0
            < item["trade_plan"]["stop"]
            < item["trade_plan"]["entry"]
            < item["trade_plan"]["target"]
        )
        assert item["trade_plan"]["id"] == plan["id"]
        assert item["price_observed_at"] == now


def test_quote_crossing_entry_invalidates_api_plan_without_moving_archived_levels(
    tmp_path,
):
    bars, now, cfg = fixture()
    store = phone.PhoneStore(tmp_path)
    plan = build_plan("BTCTRY", bars, 100, now, now, cfg)
    store.save_rally(
        "BTCTRY", {"as_of": now, "analysis": {"score": 70}, "trade_plan": plan}
    )
    store.write_metadata(
        "PHONE-MARKETS",
        {
            "tickers": {
                "BTCTRY": {"price": plan["entry"] * 0.99, "observed_at": now + 1}
            }
        },
    )
    current = phone.market_item(store, "BTCTRY", now + 1)["trade_plan"]
    assert (
        current["status"] == "STALE" and current["reference_price"] < current["entry"]
    )
    assert current["id"] == plan["id"]
    assert [current[k] for k in ("entry", "stop", "target")] == [
        plan[k] for k in ("entry", "stop", "target")
    ]
    assert store.metadata("RALLY-BTCTRY")["trade_plan"] == plan
