import copy
import pytest
from radar.api import API, audit
from radar.config import Config, HOUR
from radar.market import ingest, validate
from radar.paper import apply, new_position, track_pair
from radar.pipeline import run_pair, settle_forecasts
from radar.storage import LeaseLost, LocalStore
from conftest import FakeMarket, make_bars, raw


def test_open_bad_and_gapped_candles_are_withheld():
    bars = make_bars(4)
    now = bars[-1]["time"] + 1000
    assert len(validate([raw(b) for b in bars], now)) == 3
    bad = raw(bars[0])
    bad[2] = "-1"
    with pytest.raises(ValueError):
        validate([bad], now)
    cfg = Config(lookback_days=30)
    missing = FakeMarket([bars[0], bars[2]])
    with pytest.raises(ValueError, match="gap"):
        ingest(missing, "BTCTRY", {}, now, cfg)


def test_backfill_progress_is_saved_but_not_advertised_complete():
    bars = make_bars(3000)
    now = bars[-1]["time"] + HOUR
    cfg = Config(lookback_days=200, max_pages=1)
    first, complete = ingest(FakeMarket(bars), "BTCTRY", {}, now, cfg)
    assert len(first) == 1000 and not complete
    second, complete = ingest(FakeMarket(bars), "BTCTRY", {"bars": first}, now, cfg)
    assert len(second) == 2000 and not complete
    third, complete = ingest(FakeMarket(bars), "BTCTRY", {"bars": second}, now, cfg)
    assert len(third) == 3000 and complete


def test_lease_fencing_prevents_old_worker_overwriting_new_data(tmp_path):
    store = LocalStore(tmp_path)
    old = store.acquire("BTCTRY", "old", 100, lease_ms=10)
    assert store.acquire("BTCTRY", "parallel", 105) is None
    current = store.acquire("BTCTRY", "new", 111)
    state = {"bars": [], "backfill_complete": False, "stages": {}}
    store.commit("BTCTRY", current, state, {"winner": "new"}, 112)
    with pytest.raises(LeaseLost):
        store.commit("BTCTRY", old, state, {"winner": "old"}, 113)
    assert store.read("BTCTRY", public=True) == {"winner": "new"}


def test_failure_preserves_last_good_snapshot_and_recovery_is_async(tmp_path):
    store = LocalStore(tmp_path)
    bars = make_bars(110)
    now = bars[-1]["time"] + HOUR
    cfg = Config(pairs=("BTCTRY",), token="secret")
    run_pair(store, FakeMarket(bars), cfg, "BTCTRY", "good", now, clock=lambda: now)
    good = store.read("BTCTRY", public=True)
    bad = FakeMarket([*bars, {**bars[-1], "time": now + HOUR}])
    with pytest.raises(ValueError):
        run_pair(
            store,
            bad,
            cfg,
            "BTCTRY",
            "bad",
            now + 2 * HOUR,
            clock=lambda: now + 2 * HOUR,
        )
    assert store.read("BTCTRY", public=True) == good
    assert not audit(store, cfg, now + 2 * HOUR)["ok"]
    queued = []
    api = API(store, cfg, lambda *args: queued.append(args))
    assert api.handle("POST", "/v2/control/reconcile", "", {}, "{}", now)[0] == 401
    status, result = api.handle(
        "POST",
        "/v2/control/reconcile",
        "",
        {"Authorization": "Bearer secret"},
        "{}",
        now,
    )
    assert status == 202 and queued
    assert not result["audit"]["ok"]


def test_paper_is_idempotent_and_trade_gaps_cannot_claim_success(tmp_path):
    store = LocalStore(tmp_path)
    cfg = Config(pairs=("BTCTRY",))
    now = 1704067200000
    body = {
        "position_id": "47ce5261-3251-4b17-85f5-d64c32c058f9",
        "pair": "BTCTRY",
        "entry": 100,
        "target": 103,
        "stop": 98,
    }
    position = new_position(body, now, cfg.pairs)
    assert (
        store.create_position(position)["position_id"]
        == store.create_position(position)["position_id"]
    )
    with pytest.raises(ValueError):
        store.create_position(new_position({**body, "target": 105}, now, cfg.pairs))
    market = FakeMarket([])
    market.trade_batches = [
        [{"a": 10, "T": now + 1, "p": 100}, {"a": 12, "T": now + 2, "p": 104}]
    ]
    assert track_pair(store, market, "BTCTRY", now + 10) == "BACKFILLING"
    saved = store.positions()[0]
    assert saved["state"] == "TRACKING" and saved["feed_blocked"]
    market.trade_batches = [
        [{"a": 10, "T": now + 1, "p": 100}, {"a": 11, "T": now + 2, "p": 104}]
    ]
    assert track_pair(store, market, "BTCTRY", now + 10) == "OK"
    assert store.positions()[0]["state"] == "TARGET_HIT"


def test_target_after_deadline_is_timeout():
    position = new_position(
        {
            "position_id": "47ce5261-3251-4b17-85f5-d64c32c058f9",
            "pair": "BTCTRY",
            "entry": 100,
            "target": 103,
        },
        100,
        ("BTCTRY",),
    )
    apply(position, {"id": 1, "price": 104, "time": position["deadline_at"]})
    assert position["state"] == "TIMEOUT" and position["target_hit_at"] is None


def test_labels_only_settle_exact_complete_horizon():
    bars = make_bars(4)
    record = {
        "end_bar": bars[2]["time"],
        "price": bars[0]["close"],
        "threshold": 0.005,
        "direction": "up",
        "outcome": None,
    }
    assert (
        settle_forecasts([copy.deepcopy(record)], [bars[0], bars[3]])[0]["outcome"]
        is None
    )
    assert settle_forecasts([record], bars)[0]["outcome"] in (0, 1)


def test_forward_records_are_immutable_and_repeated_jobs_do_not_duplicate_them(
    tmp_path,
):
    store = LocalStore(tmp_path)
    bars = make_bars(800)
    now = bars[-1]["time"] + HOUR
    cfg = Config(pairs=("BTCTRY",))
    market = FakeMarket(bars)
    run_pair(store, market, cfg, "BTCTRY", "first", now, clock=lambda: now)
    first = copy.deepcopy(store.read("BTCTRY")["forward"])
    assert len(first) > 0
    run_pair(
        store, market, cfg, "BTCTRY", "retry", now + 300000, clock=lambda: now + 300000
    )
    state = store.read("BTCTRY")
    assert state["forward"] == first
    assert state["research"]["round"] == 1
    for horizon in cfg.horizons:
        assert (
            sum(
                r["probability"]
                for r in state["current_forecasts"]
                if r["horizon_hours"] == horizon
            )
            <= 1 + 1e-12
        )
