import copy
import json
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest

from radar import phone
from radar.config import Config, HOUR
from radar.quotes import live_snapshot, with_live
from radar.plans import build_plan
from conftest import make_bars


def quote(price, now, source="BINANCE_TR_WS"):
    return {
        "price": price,
        "change": 0.03,
        "quote_volume": 1000,
        "observed_at": now,
        "source": source,
    }


def save(root, tickers, **extra):
    data = {"protocol": "native-binance-tr-prices-v1", "tickers": tickers, **extra}
    (root / "PHONE-LIVE.json").write_text(json.dumps(data))


def test_native_validation_and_restart_preserve_original_timestamps(tmp_path):
    now = 500 * HOUR
    save(
        tmp_path,
        {
            "BTCTRY": quote(100, now),
            "BTCUSDT": quote(100, now),
            "ZERO3TRY": quote(0, now),
            "FUTURETRY": quote(100, now + 1),
            "OTHERTRY": quote(100, now, "other"),
        },
    )
    result = live_snapshot(tmp_path, now)
    assert set(result["tickers"]) == {"BTCTRY"}
    recovered = live_snapshot(tmp_path, now + HOUR)
    assert recovered["tickers"]["BTCTRY"]["observed_at"] == now
    (tmp_path / "PHONE-LIVE.json").write_text('{"protocol":')
    assert live_snapshot(tmp_path, now) == {}


def test_newer_native_quotes_win_without_mutating_metadata_or_fabricating_closed_bars(
    tmp_path, monkeypatch
):
    now = 500 * HOUR
    monkeypatch.setattr(phone, "clock_ms", lambda: now + 1000)
    store = phone.PhoneStore(tmp_path)
    old = {"pairs": ["BTCTRY"], "tickers": {"BTCTRY": quote(100, now)}}
    store.write_metadata("PHONE-MARKETS", old)
    save(tmp_path, {"BTCTRY": quote(99, now + 1000)})
    assert store.metadata("PHONE-MARKETS")["tickers"]["BTCTRY"]["price"] == 99
    assert json.loads((tmp_path / "PHONE-MARKETS.json").read_text()) == old
    assert not store.read("BTCTRY")
    store.write_metadata(
        "PHONE-MARKETS", {**old, "tickers": {"BTCTRY": quote(101, now + 2000)}}
    )
    assert store.metadata("PHONE-MARKETS")["tickers"]["BTCTRY"]["price"] == 101
    assert (
        with_live(old, live_snapshot(tmp_path, now + 1000))["tickers"]["BTCTRY"][
            "price"
        ]
        == 99
    )


def test_price_reads_continue_while_research_is_busy_and_quote_crossing_keeps_anchor(
    tmp_path, monkeypatch
):
    bars = make_bars(120)
    now = bars[-1]["time"] + HOUR + 1000
    clock = [now]
    monkeypatch.setattr(phone, "clock_ms", lambda: clock[0])
    store = phone.PhoneStore(tmp_path)
    cfg = Config(pairs=("BTCTRY",))
    plan = build_plan("BTCTRY", bars, 100, now, now, cfg)
    store.write_metadata(
        "PHONE-MARKETS", {"pairs": ["BTCTRY"], "tickers": {"BTCTRY": quote(100, now)}}
    )
    store.save_rally(
        "BTCTRY", {"as_of": now, "analysis": {"score": 72}, "trade_plan": plan}
    )
    started, release = threading.Event(), threading.Event()

    def research():
        assert store.acquire("BTCTRY", "slow", now)
        started.set()
        assert release.wait(5)

    with ThreadPoolExecutor(max_workers=1) as pool:
        task = pool.submit(research)
        assert started.wait(5)
        for offset, price in [(1000, 101), (2000, plan["entry"] * 0.9)]:
            clock[0] = now + offset
            save(tmp_path, {"BTCTRY": quote(price, clock[0])})
            body = json.loads(
                phone.request(str(tmp_path), "[]", "GET", "/v2/snapshot")
            )["body"]
            item = body["items"][0]
            assert item["price"] == price and item["price_observed_at"] == clock[0]
            assert (
                item["rally"]["as_of"] == now
            )  # quote update is not a score/research update
            assert (
                item["trade_plan"]["entry"] == plan["entry"]
                and item["trade_plan"]["stop"] == plan["stop"]
            )
        assert item["trade_plan"]["status"] == "STALE"
        release.set()
        task.result(timeout=5)
    assert store.metadata("RALLY-BTCTRY")["trade_plan"] == plan


def test_native_rest_rate_limit_is_shared_with_python_requests(tmp_path, monkeypatch):
    now = 500 * HOUR
    monkeypatch.setattr(phone, "clock_ms", lambda: now)
    store = phone.PhoneStore(tmp_path)
    save(tmp_path, {}, backoff_until=now + 60_000)
    market = phone.PhoneMarket()
    market.backoff_store = store
    with pytest.raises(OSError, match="Retry-After"):
        market.json("https://api.binance.me", "/api/v1/ticker/24hr")
    assert store.metadata("PHONE-MARKETS")["backoff_until"] == now + 60_000


def test_native_fresh_quotes_remove_duplicate_bulk_ticker_requests(
    tmp_path, monkeypatch
):
    now = 500 * HOUR
    monkeypatch.setattr(phone, "clock_ms", lambda: now)
    store = phone.PhoneStore(tmp_path)
    store.write_metadata("PHONE-MARKETS", {"pairs": ["BTCTRY"], "discovered_at": now})
    save(tmp_path, {"BTCTRY": quote(103, now)})

    class Market:
        def tickers(self, at):
            pytest.fail("Redundant REST ticker request")

        def rally(self, pair, btc):
            return {
                "pair": pair,
                "as_of": now,
                "analysis": {"score": 70},
                "chart": {"hour": []},
            }

    monkeypatch.setattr(phone, "PhoneMarket", Market)
    phone.cycle(str(tmp_path), "[]", score_only=True)
    assert phone.market_item(store, "BTCTRY", now)["price"] == 103


def test_live_cache_is_in_the_existing_drive_zip_and_reads_do_not_inflate_research(
    tmp_path, monkeypatch
):
    now = 500 * HOUR
    monkeypatch.setattr(phone, "clock_ms", lambda: now)
    store = phone.PhoneStore(tmp_path / "data")
    save(store.root, {"BTCTRY": quote(100, now)})
    before = copy.deepcopy(store.metadata("BTCTRY"))
    for _ in range(10):
        phone.market_item(store, "BTCTRY", now)
    assert store.metadata("BTCTRY") == before
    import zipfile

    archive = phone.backup(str(store.root), '["BTCTRY"]')
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        assert (
            json.loads(z.read("state/PHONE-LIVE.json"))["tickers"]["BTCTRY"][
                "observed_at"
            ]
            == now
        )
