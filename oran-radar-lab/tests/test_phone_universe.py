import importlib.util
import json
import math
import sqlite3
import sys
import types
import urllib.error
import zipfile
from pathlib import Path

import pytest

from conftest import make_bars
from radar import phone
from radar.config import Config, HOUR, PERIOD
from radar.rally import score_market


def test_rally_preserves_original_six_component_score(monkeypatch):
    # Compare with the actual original function, not a second copied formula.
    monkeypatch.setitem(sys.modules, "market", types.SimpleNamespace(align_price=None))
    source = Path(__file__).parents[1] / "backend" / "engine.py"
    spec = importlib.util.spec_from_file_location("original_engine", source)
    original = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(original)
    bars = make_bars(180)
    for change in [-0.08, -0.01, 0, 0.02, 0.08]:
        for imbalance in [0.1, 0.5, 0.9]:
            book = {
                "bids": [[99, 10 * imbalance]],
                "asks": [[101, 10 * (1 - imbalance)]],
            }
            actual = score_market(bars, bars[-120:], book, change)
            expected = original.score_market(bars, bars[-120:], book, change)
            assert {key: actual[key] for key in expected} == expected
            assert 0 <= actual["score"] <= 100
            assert actual["probability"] is False
            assert sum(actual["weights"].values()) == pytest.approx(1)
    assert score_market(bars[:79], bars, book) is None
    with pytest.raises(ValueError):
        score_market(bars, bars, {"bids": [], "asks": []})
    with pytest.raises(ValueError):
        score_market(bars, bars, {"bids": [[math.nan, 1]], "asks": [[101, 1]]})


def test_universe_includes_every_active_try_pair_without_liquidity_or_count_cutoff(
    monkeypatch,
):
    pairs = [f"COIN{i:03}TRY" for i in range(127)] + ["USDTTRY"]
    rows = [
        {
            "symbol": p[:-3] + "_TRY",
            "quoteAsset": "TRY",
            "type": 1,
            "spotTradingEnable": 1,
        }
        for p in pairs
    ]
    rows += [
        {"symbol": "OLD_TRY", "quoteAsset": "TRY", "type": 1, "spotTradingEnable": 0},
        {"symbol": "BTC_USDT", "quoteAsset": "USDT", "type": 1, "spotTradingEnable": 1},
    ]
    market = phone.PhoneMarket()
    monkeypatch.setattr(market, "json", lambda *args, **kwargs: {"list": rows})
    assert set(market.symbols()) == set(pairs)
    monkeypatch.setattr(
        market,
        "get",
        lambda *args, **kwargs: [
            {
                "symbol": p,
                "lastPrice": "100",
                "priceChangePercent": "2",
                "quoteVolume": "1",
            }
            for p in pairs
        ],
    )
    assert set(market.tickers(10)) == set(pairs)
    assert market.tickers(10)[pairs[0]]["change"] == 0.02
    assert len(phone.config(json.dumps(pairs)).pairs) == 128
    assert phone.config("[]").pairs == ()
    with pytest.raises(ValueError):
        Config(pairs=())


def test_rally_excludes_open_candles_and_rejects_source_gaps(monkeypatch):
    now = 200 * HOUR + 30_000
    monkeypatch.setattr(phone, "clock_ms", lambda: now)

    def raw(start, count, interval):
        return [
            [
                start + i * interval,
                100,
                102,
                99,
                101,
                10,
                start + (i + 1) * interval - 1,
                1000,
                2,
                4,
                400,
            ]
            for i in range(count)
        ]

    hour = raw(120 * HOUR, 81, HOUR)
    minute = raw(200 * HOUR - 120 * 60_000, 121, 60_000)
    market = phone.PhoneMarket()
    monkeypatch.setattr(
        market,
        "get",
        lambda path, **kwargs: hour if kwargs["interval"] == "1h" else minute,
    )
    monkeypatch.setattr(
        market,
        "json",
        lambda *args, **kwargs: {"bids": [[100, 10]], "asks": [[102, 10]]},
    )
    first = market.rally("BTCTRY", 0)
    assert len(first["chart"]["hour"]) == 80
    assert len(first["chart"]["minute"]) == 120
    hour[-1][4] = minute[-1][4] = 100000
    assert market.rally("BTCTRY", 0)["analysis"] == first["analysis"]
    hour.pop(-3)
    with pytest.raises(ValueError, match="gap"):
        market.rally("BTCTRY", 0)


def fake_all_market(monkeypatch, pairs, seconds, scored, researched):
    class Market:
        def symbols(self):
            return pairs

        def tickers(self, now):
            return {p: {"price": 100, "observed_at": now, "change": 0} for p in pairs}

        def rally(self, pair, change):
            scored.append(pair)
            seconds[0] += 70
            return {
                "pair": pair,
                "as_of": phone.clock_ms(),
                "analysis": {"score": 76},
                "error": None,
                "chart": {"minute": [], "hour": [], "as_of": phone.clock_ms()},
            }

    def run(store, market, cfg, pair, job, allow_training):
        researched.append(pair)
        seconds[0] += 160
        return {"pair": pair, "status": "OK"}

    monkeypatch.setattr(phone, "PhoneMarket", Market)
    monkeypatch.setattr(phone, "run_pair", run)
    monkeypatch.setattr(phone.time, "monotonic", lambda: seconds[0])
    return Market


def test_independent_durable_cursors_reach_all_coins_across_short_background_jobs(
    tmp_path, monkeypatch
):
    pairs = [f"COIN{i:03}TRY" for i in range(31)]
    seconds, scored, researched = [0], [], []
    fake_all_market(monkeypatch, pairs, seconds, scored, researched)
    first = json.loads(phone.cycle(str(tmp_path), "[]"))
    assert first["coverage"]["total"] == 31
    assert scored == pairs[:2] and researched == pairs[:2]
    # Restarted calls reconstruct cursors from disk, not a singleton variable.
    for _ in range(16):
        phone.cycle(str(tmp_path), "[]")
    assert set(scored) == set(pairs) and set(researched) == set(pairs)
    snapshot = json.loads(phone.request(str(tmp_path), "[]", "GET", "/v2/snapshot"))[
        "body"
    ]
    assert len(snapshot["items"]) == 31
    assert snapshot["expected_pairs"] == pairs
    assert snapshot["items"][-1]["rally_score"] == 76
    assert snapshot["items"][-1]["forecasts"] == []
    with sqlite3.connect(tmp_path / "research.sqlite3") as db:
        assert (
            db.execute("SELECT COUNT(*) FROM records WHERE kind='rally'").fetchone()[0]
            >= 31
        )
        assert "chart" not in json.loads(
            db.execute(
                "SELECT payload FROM records WHERE kind='rally' LIMIT 1"
            ).fetchone()[0]
        )
    with zipfile.ZipFile(phone.backup(str(tmp_path), "[]")) as archive:
        assert "state/PHONE-PROGRESS.json" in archive.namelist()
        assert "state/PHONE-MARKETS.json" in archive.namelist()
        assert "state/RALLY-" + pairs[-1] + ".json" in archive.namelist()


def test_active_paper_priority_does_not_starve_universe_rotation(tmp_path, monkeypatch):
    pairs = [f"COIN{i:03}TRY" for i in range(25)]
    seconds, scored, researched = [0], [], []
    fake_all_market(monkeypatch, pairs, seconds, scored, researched)
    store = phone.PhoneStore(tmp_path)
    for pair in pairs[-3:]:
        store.write_metadata(
            "PAPER-" + pair, {"pair": pair, "position_id": pair, "state": "TRACKING"}
        )
    for _ in range(30):
        phone.cycle(str(tmp_path), "[]")
    assert set(researched) == set(pairs)
    assert researched[0] in pairs[-3:]


def test_discovery_failure_keeps_cached_universe_and_recovery_metadata(
    tmp_path, monkeypatch
):
    pairs = [f"COIN{i:03}TRY" for i in range(20)]
    seconds, scored, researched = [0], [], []
    market = fake_all_market(monkeypatch, pairs, seconds, scored, researched)
    store = phone.PhoneStore(tmp_path)
    store.write_metadata(
        "PHONE-MARKETS", {"pairs": pairs, "discovered_at": phone.clock_ms() - 2 * HOUR}
    )
    monkeypatch.setattr(market, "symbols", lambda self: [])
    result = json.loads(phone.cycle(str(tmp_path), "[]"))
    assert result["results"][0]["status"] == "FAILED"
    assert phone.config("[]", tmp_path).pairs == tuple(pairs)
    snapshot = json.loads(phone.request(str(tmp_path), "[]", "GET", "/v2/snapshot"))[
        "body"
    ]
    assert len(snapshot["items"]) == 20
    assert any(
        i["code"] == "MARKET_DISCOVERY_FAILED" for i in snapshot["audit"]["issues"]
    )


def test_rate_limit_persists_and_stops_market_requests_until_retry_time(
    tmp_path, monkeypatch
):
    pairs = [f"COIN{i:03}TRY" for i in range(20)]
    seconds, scored, researched = [0], [], []
    market = fake_all_market(monkeypatch, pairs, seconds, scored, researched)

    def limited(self, pair, change):
        scored.append(pair)
        raise urllib.error.HTTPError(
            "https://api.binance.me",
            429,
            "Too many requests",
            {"Retry-After": "120"},
            None,
        )

    monkeypatch.setattr(market, "rally", limited)
    phone.cycle(str(tmp_path), "[]")
    assert scored == pairs[:1] and not researched
    assert (
        phone.PhoneStore(tmp_path).metadata("PHONE-MARKETS")["backoff_until"]
        > phone.clock_ms()
    )
    result = json.loads(phone.cycle(str(tmp_path), "[]"))
    assert result["results"][0]["status"] == "FAILED"
    assert scored == pairs[:1]


def test_scores_stay_separate_from_research_and_stale_values_are_labelled(
    tmp_path, monkeypatch
):
    now = 500 * HOUR
    monkeypatch.setattr(phone, "clock_ms", lambda: now)
    store = phone.PhoneStore(tmp_path)
    store.write_metadata(
        "PHONE-MARKETS",
        {
            "pairs": ["BTCTRY", "ETHTRY"],
            "discovered_at": now,
            "tickers": {"BTCTRY": {"price": 101, "observed_at": now}},
        },
    )
    store.save_rally(
        "BTCTRY",
        {
            "pair": "BTCTRY",
            "as_of": now - 4 * PERIOD,
            "analysis": {"score": 82},
            "error": None,
            "chart": {"minute": [], "hour": [], "as_of": now},
        },
    )
    data = json.loads(phone.request(str(tmp_path), "[]", "GET", "/v2/snapshot"))["body"]
    assert data["items"][0]["rally_score"] == 82
    assert data["items"][0]["rally"]["status"] == "STALE"
    assert data["items"][0]["forecasts"] == []
    assert data["items"][1]["rally_score"] is None
    assert data["items"][1]["rally"]["status"] == "WAITING"
    assert data["coverage"]["scores_current"] == 0
    market = json.loads(
        phone.request(str(tmp_path), "[]", "GET", "/market", "pair=BTCTRY")
    )
    assert (
        market["status"] == 200 and market["body"]["opportunity"]["rally_score"] == 82
    )
    assert market["body"]["opportunity"]["scenario"] is None
    assert market["body"]["research"]["rally"]["status"] == "STALE"
