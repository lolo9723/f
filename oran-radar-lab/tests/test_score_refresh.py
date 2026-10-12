import json
import threading
from concurrent.futures import ThreadPoolExecutor
import urllib.error
import urllib.request

import pytest

from radar import phone
from radar.config import HOUR


def quick_market(monkeypatch, pairs, now, seconds, calls, seconds_per_pair=0):
    class Market:
        def symbols(self):
            return pairs

        def tickers(self, at):
            return {pair: {"price": now[0] / 1000, "observed_at": at} for pair in pairs}

        def rally(self, pair, change):
            calls.append(pair)
            seconds[0] += seconds_per_pair
            return {
                "pair": pair,
                "as_of": now[0],
                "analysis": {"score": 40 + len(calls) % 50},
                "chart": {"hour": []},
                "error": None,
            }

    monkeypatch.setattr(phone, "PhoneMarket", Market)
    monkeypatch.setattr(phone, "clock_ms", lambda: now[0])
    monkeypatch.setattr(phone.time, "monotonic", lambda: seconds[0])
    return Market


def test_score_only_changes_api_values_without_running_research(tmp_path, monkeypatch):
    pairs, now, seconds, calls = ["BTCTRY", "ETHTRY"], [500 * HOUR], [0], []
    quick_market(monkeypatch, pairs, now, seconds, calls)
    monkeypatch.setattr(
        phone,
        "run_pair",
        lambda *a, **k: pytest.fail("Score refresh ran deep research"),
    )

    def scan():
        return json.loads(
            phone.cycle(str(tmp_path), "[]", score_only=True, score_budget=20)
        )

    assert scan()["mode"] == "SCORE_ONLY"
    first = json.loads(phone.request(str(tmp_path), "[]", "GET", "/v2/snapshot"))[
        "body"
    ]
    now[0] += 30_000
    scan()
    second = json.loads(phone.request(str(tmp_path), "[]", "GET", "/v2/snapshot"))[
        "body"
    ]
    assert first["items"][0]["rally_score"] != second["items"][0]["rally_score"]
    assert second["items"][0]["rally"]["as_of"] == now[0]
    assert second["coverage"]["last_score_cycle"] == now[0]
    assert len(calls) == 4


def test_visible_priority_with_single_pair_budget_still_reaches_all_coins(
    tmp_path, monkeypatch
):
    pairs = [f"COIN{i:03}TRY" for i in range(31)]
    now, seconds, calls = [500 * HOUR], [0], []
    quick_market(monkeypatch, pairs, now, seconds, calls, seconds_per_pair=25)
    for _ in range(65):
        phone.cycle(
            str(tmp_path),
            "[]",
            score_only=True,
            priorities_json=json.dumps([pairs[-1]]),
            score_budget=20,
        )
        now[0] += 30_000
    assert calls[0] == pairs[-1]
    assert set(calls) == set(pairs)
    assert calls.count(pairs[-1]) > 20
    assert "research_next" not in phone.PhoneStore(tmp_path).metadata("PHONE-PROGRESS")


def test_concurrent_research_cannot_overwrite_new_score_cursor_quote_or_backoff(
    tmp_path, monkeypatch
):
    pairs, now, seconds, calls = ["BTCTRY", "ETHTRY"], [500 * HOUR], [0], []
    quick_market(monkeypatch, pairs, now, seconds, calls)
    store = phone.PhoneStore(tmp_path)
    store.write_metadata(
        "PHONE-MARKETS",
        {"pairs": pairs, "discovered_at": now[0], "tickers": {"BTCTRY": {"price": 1}}},
    )
    started, release = threading.Event(), threading.Event()

    def research(*args, **kwargs):
        started.set()
        assert release.wait(5)
        return {"status": "OK"}

    monkeypatch.setattr(phone, "run_pair", research)
    with ThreadPoolExecutor(max_workers=1) as executor:
        future = executor.submit(phone.cycle, str(tmp_path), "[]", False, False, False)
        assert started.wait(5)
        phone.cycle(str(tmp_path), "[]", score_only=True, score_budget=20)
        progress = store.metadata("PHONE-PROGRESS")
        store.save_backoff(now[0] + 120_000)
        release.set()
        future.result(timeout=5)
    assert (
        store.metadata("PHONE-MARKETS")["tickers"]["BTCTRY"]["price"] == now[0] / 1000
    )
    assert store.metadata("PHONE-MARKETS")["backoff_until"] == now[0] + 120_000
    assert store.metadata("PHONE-PROGRESS")["score_next"] == progress["score_next"]
    assert store.metadata("PHONE-PROGRESS")["last_score_cycle"] == now[0]
    assert store.metadata("PHONE-PROGRESS")["research_next"] == "ETHTRY"


def test_score_failure_keeps_old_score_and_shared_rate_limit_blocks_both_jobs(
    tmp_path, monkeypatch
):
    pairs, now, seconds, calls = ["BTCTRY"], [500 * HOUR], [0], []
    market = quick_market(monkeypatch, pairs, now, seconds, calls)
    phone.cycle(str(tmp_path), "[]", score_only=True, score_budget=20)
    previous = phone.PhoneStore(tmp_path).metadata("RALLY-BTCTRY")

    def limited(*args):
        raise urllib.error.HTTPError(
            "https://api.binance.me", 429, "limited", {"Retry-After": "120"}, None
        )

    monkeypatch.setattr(market, "rally", limited)
    now[0] += 30_000
    value = json.loads(
        phone.cycle(str(tmp_path), "[]", score_only=True, score_budget=20)
    )
    assert value["results"][0]["phase"] == "SCORE"
    after = phone.PhoneStore(tmp_path).metadata("RALLY-BTCTRY")
    assert (
        after["analysis"] == previous["analysis"]
        and after["as_of"] == previous["as_of"]
    )
    monkeypatch.setattr(
        phone, "run_pair", lambda *a, **k: pytest.fail("Research ignored backoff")
    )
    assert (
        json.loads(phone.cycle(str(tmp_path), "[]", run_scores=False))["results"][0][
            "status"
        ]
        == "FAILED"
    )
    assert (
        json.loads(phone.cycle(str(tmp_path), "[]", score_only=True))["results"][0][
            "status"
        ]
        == "FAILED"
    )


def test_closed_history_cache_rolls_at_minute_and_hour_boundaries(monkeypatch):
    now = [500 * HOUR + 10_000]
    monkeypatch.setattr(phone, "clock_ms", lambda: now[0])
    calls = []
    market = phone.PhoneMarket()

    def get(path, **kwargs):
        interval = HOUR if kwargs["interval"] == "1h" else 60_000
        count = kwargs["limit"]
        calls.append(kwargs["interval"])
        start = now[0] // interval * interval - (count - 1) * interval
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

    books = []

    def book(*a, **k):
        books.append(now[0])
        return {"bids": [[100, 10]], "asks": [[102, 10]]}

    monkeypatch.setattr(market, "get", get)
    monkeypatch.setattr(market, "json", book)
    first = market.rally("BTCTRY", 0)
    assert calls == ["1h", "1m"]
    market.cached_rally = first
    now[0] += 30_000
    second = market.rally("BTCTRY", 0)
    assert calls == ["1h", "1m"] and len(books) == 2
    assert (
        second["as_of"] != first["as_of"]
        and second["analysis"]["score"] == first["analysis"]["score"]
    )
    market.cached_rally = second
    now[0] += 30_000
    market.cached_rally = market.rally("BTCTRY", 0)
    assert calls == ["1h", "1m", "1m"]
    now[0] = 501 * HOUR + 1000
    market.rally("BTCTRY", 0)
    assert calls[-2:] == ["1h", "1m"]


def test_http_rate_limit_is_shared_immediately_before_next_lane_can_send(
    tmp_path, monkeypatch
):
    now = 500 * HOUR
    monkeypatch.setattr(phone, "clock_ms", lambda: now)
    store = phone.PhoneStore(tmp_path)
    market = phone.PhoneMarket()
    market.backoff_store = store
    attempts = []

    def limited(*args, **kwargs):
        attempts.append(1)
        raise urllib.error.HTTPError(
            "https://api.binance.me", 429, "limited", {"Retry-After": "120"}, None
        )

    monkeypatch.setattr(urllib.request, "urlopen", limited)
    with pytest.raises(urllib.error.HTTPError):
        market.json("https://api.binance.me", "/api/v3/depth")
    assert store.metadata("PHONE-MARKETS")["backoff_until"] == now + 120_000
    other_lane = phone.PhoneMarket()
    other_lane.backoff_store = store
    with pytest.raises(OSError, match="Retry-After"):
        other_lane.json("https://api.binance.me", "/api/v1/klines")
    assert attempts == [1]
