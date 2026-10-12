"""Private on-device engine. Called directly from Android; no HTTP server or credentials.

Snapshots use the existing fenced LocalStore. A SQLite journal retains distinct
closed candles, model evaluations, forecasts and stop sessions beyond UI limits.
Research and score jobs have separate native locks. Atomic file merges guard
shared metadata; snapshot reads never wait for a whole research cycle.
"""

import json
import math
import os
import re
import sqlite3
import time
import uuid
import zipfile

from .config import Config, HOUR, PERIOD
from .api import API
from .market import Market
from .market import validate
from .paper import needs_tracking
from .pipeline import run_pair
from .rally import score_market
from .research_score import blend as blend_score
from .plans import build_plan, current_plan, rebase, waiting
from .storage import LocalStore, clock_ms
from .storage import decode
from .quotes import live_snapshot, with_live


class PhoneStore(LocalStore):
    runtime = "PHONE"

    def metadata(self, key):
        value = super().metadata(key)
        if key == "PHONE-MARKETS":
            value = with_live(value, live_snapshot(self.root, clock_ms()))
        return value

    def write_metadata(self, key, value):
        with self.locked():
            self._write(key, value)

    def merge_metadata(self, key, changes):
        with self.locked():
            value = self._metadata(key)
            value.update(changes)
            self._write(key, value)
            return value

    def save_backoff(self, until):
        with self.locked():
            value = self._metadata("PHONE-MARKETS")
            value["backoff_until"] = max(value.get("backoff_until", 0), until)
            self._write("PHONE-MARKETS", value)

    def save_rally(self, pair, value):
        self.write_metadata("RALLY-" + pair, value)
        with sqlite3.connect(self.root / "research.sqlite3", timeout=30) as db:
            db.execute(
                "CREATE TABLE IF NOT EXISTS records (kind TEXT, id TEXT, payload TEXT, updated_at INTEGER, PRIMARY KEY(kind,id))"
            )
            db.execute(
                "INSERT OR IGNORE INTO records VALUES (?,?,?,?)",
                (
                    "rally",
                    f"{pair}:{value['as_of']}",
                    json.dumps(
                        {k: v for k, v in value.items() if k != "chart"},
                        allow_nan=False,
                    ),
                    value["as_of"],
                ),
            )

    def read(self, pair, public=False):
        with self.locked():
            meta = self._metadata(pair)
            key = meta.get("public_key" if public else "key")
            return decode((self.root / key).read_bytes()) if key else None

    def paper_market(self, pair, now):
        return market_item(self, pair, now, self.read(pair, public=True))

    def journal(self, pair, state):
        with sqlite3.connect(self.root / "research.sqlite3", timeout=30) as db:
            db.execute(
                "CREATE TABLE IF NOT EXISTS records (kind TEXT, id TEXT, payload TEXT, updated_at INTEGER, PRIMARY KEY(kind,id))"
            )

            def save(kind, key, value, immutable=False):
                sql = "INSERT OR IGNORE" if immutable else "INSERT OR REPLACE"
                db.execute(
                    f"{sql} INTO records VALUES (?,?,?,?)",
                    (
                        kind,
                        key,
                        json.dumps(value, allow_nan=False),
                        state["completed_at"],
                    ),
                )

            for bar in state["bars"]:
                save("candle", f"{pair}:{bar['time']}", bar, True)
            if state.get("trade_plan", {}).get("id"):
                save("trade_plan", state["trade_plan"]["id"], state["trade_plan"], True)
            if (
                state.get("latest_features")
                and state.get("bars")
                and state.get("stages", {}).get("features") == "OK"
            ):
                observed_at = state["bars"][-1]["time"]
                save(
                    "features",
                    f"{pair}:{observed_at}",
                    {
                        "pair": pair,
                        "observed_at": observed_at,
                        "recorded_at": state["completed_at"],
                        "features": state["latest_features"],
                        "stop_context": state.get("stop_context", {}),
                        "source": state.get("source"),
                        "numerics": state["numerics"],
                    },
                    True,
                )
            for key in ("research", "stop_research", "entry_research"):
                report = state.get(key)
                if report:
                    save(key, f"{pair}:{report.get('round', 0)}", report, True)
            for row in state.get("forward", []):
                save("forecast", row["id"], row)
            for row in state.get("stop_sessions", []):
                save("stop_session", row["id"], row)
            for row in self.positions():
                save("paper", row["position_id"], row)

    def commit(self, pair, token, state, public, now):
        state["numerics"] = public["numerics"] = "portable-lbfgs-v1; numpy-1.26.2"
        ticker = self.metadata("PHONE-MARKETS").get("tickers", {}).get(pair, {})
        if ticker.get("price") is not None and state.get("trade_plan"):
            state["trade_plan"] = public["trade_plan"] = rebase(
                state["trade_plan"], ticker["price"], ticker.get("observed_at"), now
            )
        super().commit(pair, token, state, public, now)
        self.journal(pair, state)
        # read() and pruning share the file lock: readers keep their revision
        # alive while resolving the pointer and decoding the public payload.
        # Keep the current and immediately previous pair revisions, not every poll.
        files = sorted(
            self.root.glob(f"{pair}-*.json.gz"),
            key=lambda p: p.stat().st_mtime,
            reverse=True,
        )
        with self.locked():
            for file in files[4:]:
                file.unlink(missing_ok=True)


def config(pairs_json, root=None):
    pairs = tuple(json.loads(pairs_json))
    automatic = not pairs
    if automatic and root is not None:
        store = PhoneStore(root)
        pairs = tuple(store.metadata("PHONE-MARKETS").get("pairs", []))
        if not pairs:
            pairs = tuple(
                sorted(
                    file.stem
                    for file in store.root.glob("*TRY.json")
                    if re.fullmatch(r"[A-Z0-9]{2,20}TRY", file.stem)
                )
            )
    if len(set(pairs)) != len(pairs):
        raise ValueError("Choose distinct TRY pairs")
    return Config(pairs=pairs, token="", max_pages=2, discover_pairs=automatic)


def market_item(store, pair, now, payload=None, universe=None, cfg=None):
    if universe is None:
        universe = store.metadata("PHONE-MARKETS")
    ticker = universe.get("tickers", {}).get(pair, {})
    rally = store.metadata("RALLY-" + pair)
    analysis = rally.get("analysis", {})
    recent = bool(rally.get("as_of") and 0 <= now - rally["as_of"] <= 3 * PERIOD)
    result = payload or {
        "pair": pair,
        "display": pair[:-3] + "/TRY",
        "coin": pair[:-3],
        "source": "Binance TR",
        "price": ticker.get("price"),
        "observed_at": ticker.get("observed_at"),
        "as_of": 0,
        "bars": 0,
        "backfill_complete": False,
        "stale": True,
        "forecasts": [],
        "hypotheses": [],
        "hypothesis_count": 0,
        "interpretation": [],
        "monitor": {},
        "research": {},
        "issues": ["BACKFILL_INCOMPLETE"],
    }
    plans = [
        p
        for p in (result.get("trade_plan"), rally.get("trade_plan"))
        if p and p.get("id")
    ]
    result["trade_plan"] = (
        rebase(
            max(plans, key=lambda p: p["as_of"]),
            ticker["price"],
            ticker.get("observed_at"),
            now,
        )
        if plans and ticker.get("price") is not None
        else current_plan(max(plans, key=lambda p: p["as_of"]), now)
        if plans
        else rally.get("trade_plan")
        or result.get("trade_plan")
        or waiting(
            pair, "Alış, hedef ve stop önerisi için ilk piyasa taraması bekleniyor."
        )
    )
    analysis = blend_score(
        analysis,
        result,
        cfg or config(json.dumps(universe.get("pairs") or [pair])),
        pair,
        rally.get("as_of"),
        now,
        rally.get("error"),
    )
    score = analysis.get("score")
    result["rally_score"] = score
    result["rally"] = {
        "status": "CURRENT"
        if score is not None and recent and not rally.get("error")
        else "STALE"
        if score is not None
        else "WAITING",
        "as_of": rally.get("as_of"),
        "error": rally.get("error"),
        "analysis": analysis,
    }
    result["price_source"] = ticker.get("source", "BINANCE_TR_REST")
    result["price_status"] = (
        "CURRENT"
        if ticker.get("observed_at") and 0 <= now - ticker["observed_at"] <= 30_000
        else "STALE"
    )
    result["price_observed_at"] = ticker.get("observed_at") or result.get("observed_at")
    if ticker.get("price") is not None:
        result["price"] = ticker["price"]
    return result


def request(root, pairs_json, method, path, query="", body="{}"):
    store, cfg = PhoneStore(root), config(pairs_json, root)
    queued = []
    api = API(store, cfg, lambda pair, job, force: queued.append(pair))
    now = clock_ms()
    status, payload = api.handle(method, path, query, {}, body, now)
    if method == "GET" and path == "/v2/snapshot" and status == 200:
        existing = {item["pair"]: item for item in payload["items"]}
        universe = store.metadata("PHONE-MARKETS")
        payload["items"] = [
            market_item(store, pair, now, existing.get(pair), universe, cfg)
            for pair in cfg.pairs
        ]
        payload["coverage"] = coverage(store, cfg, now)
    if method == "GET" and path in ("/market", "/candles", "/pairs"):
        from urllib.parse import parse_qs

        pair = parse_qs(query).get("pair", [""])[0]
        if path == "/pairs":
            universe = store.metadata("PHONE-MARKETS")
            payload = []
            for p in cfg.pairs:
                item = market_item(
                    store, p, now, store.read(p, public=True), universe, cfg
                )
                payload.append(
                    {
                        k: item.get(k)
                        for k in (
                            "pair",
                            "display",
                            "price",
                            "observed_at",
                            "price_observed_at",
                            "trade_plan",
                        )
                    }
                )
        elif pair in cfg.pairs:
            rally = store.metadata("RALLY-" + pair)
            item = market_item(store, pair, now, store.read(pair, public=True), cfg=cfg)
            if path == "/market":
                score = item["rally_score"]
                opportunity = (
                    {
                        "pair": pair,
                        "display": item["display"],
                        "coin": pair[:-3],
                        "price": item["price"],
                        "rally_score": score,
                        "state": "WATCH"
                        if score is None or score < 55
                        else "SCOUT"
                        if score < 70
                        else "SETUP",
                        "spread_pct": item["rally"]["analysis"].get("spread_pct", 0),
                        "scenario": None,
                        "features": item["rally"]["analysis"],
                        "observed_at": rally.get("as_of") or item.get("observed_at"),
                        "price_observed_at": item.get("price_observed_at"),
                        "trade_plan": item.get("trade_plan"),
                    }
                    if item["price"] is not None
                    else None
                )
                status, payload = (
                    200,
                    {"pair": pair, "opportunity": opportunity, "research": item},
                )
            elif rally.get("chart"):
                status, payload = 200, rally["chart"]
    if path in ("/v2/snapshot", "/v2/audit", "/v2/control/reconcile") and status < 400:
        check = payload if path == "/v2/audit" else payload["audit"]
        universe = store.metadata("PHONE-MARKETS")
        if cfg.discover_pairs and (not universe.get("pairs") or universe.get("error")):
            check["ok"] = False
            check["issues"].append(
                {
                    "pair": None,
                    "code": "MARKET_DISCOVERY_FAILED"
                    if universe.get("error")
                    else "MARKET_DISCOVERY_PENDING",
                }
            )
        for pair in cfg.pairs:
            row = store.metadata("RALLY-" + pair)
            code = (
                "RALLY_FAILED"
                if row.get("error")
                else "RALLY_PENDING"
                if row.get("analysis", {}).get("score") is None
                else "RALLY_STALE"
                if now - row.get("as_of", 0) > 3 * PERIOD
                else None
            )
            if code:
                check["ok"] = False
                check["issues"].append({"pair": pair, "code": code})
        check["scope"] += ", phone market discovery and original Rally Score freshness"
    # Match HTTP error semantics in JS without a loopback network server.
    return json.dumps(
        {"status": status, "body": payload, "queued": queued}, allow_nan=False
    )


def cycle(
    root,
    pairs_json,
    charging=False,
    score_only=False,
    run_scores=True,
    priorities_json="[]",
    score_budget=120,
):
    store = PhoneStore(root)
    market = PhoneMarket()
    market.backoff_store = store
    now = clock_ms()
    store.heartbeat(now)
    results = []
    deadline = time.monotonic() + (score_budget if score_only else 420)
    universe = store.metadata("PHONE-MARKETS")
    if universe.get("backoff_until", 0) > now:
        return json.dumps(
            {
                "as_of": now,
                "results": [
                    {
                        "pair": None,
                        "status": "FAILED",
                        "error": "Borsa hız sınırı; sonraki işte devam edilecek.",
                    }
                ],
                "coverage": coverage(store, config(pairs_json, root), now),
            }
        )
    automatic = not json.loads(pairs_json)
    try:
        if (
            run_scores
            and automatic
            and (
                not universe.get("pairs")
                or now - universe.get("discovered_at", 0) >= HOUR
            )
        ):
            discovered = market.symbols()
            if not discovered:
                raise ValueError("Empty active TRY universe; previous list retained")
            universe.update(pairs=discovered, discovered_at=now, error=None)
            store.merge_metadata(
                "PHONE-MARKETS", {"pairs": discovered, "discovered_at": now}
            )
        native = live_snapshot(store.root, now)
        recent = [
            row
            for row in native.get("tickers", {}).values()
            if 0 <= now - row["observed_at"] <= 30_000
        ]
        if run_scores and hasattr(market, "tickers") and not recent:
            universe["tickers"] = market.tickers(now)
        universe = with_live(universe, native)
        if run_scores:
            universe["error"] = None
            store.merge_metadata(
                "PHONE-MARKETS",
                {k: v for k, v in universe.items() if k != "backoff_until"},
            )
    except Exception as exc:
        universe["error"] = str(exc)[:400]
        rate_limited(universe, exc, clock_ms())
        store.merge_metadata("PHONE-MARKETS", {"error": universe["error"]})
        store.save_backoff(universe.get("backoff_until", 0))
        results.append(
            {
                "pair": None,
                "status": "FAILED",
                "error": "Piyasa listesi/fiyatları: " + str(exc)[:300],
            }
        )
    cfg = config(pairs_json, root)
    progress = store.metadata("PHONE-PROGRESS")
    # Separate durable cursors: deep backfill never blocks everybody's Rally Score.
    score_deadline = min(deadline, time.monotonic() + score_budget)
    if (
        run_scores
        and hasattr(market, "rally")
        and universe.get("backoff_until", 0) <= clock_ms()
    ):
        normal_scores = rotated(cfg.pairs, progress.get("score_next"))
        visible = set(json.loads(priorities_json)) & set(cfg.pairs)
        priority_scores = rotated(sorted(visible), progress.get("score_priority_next"))
        order, seen = [], set()
        # Alternate visible and normal coins: a slow visible pair cannot consume
        # every budget forever. Rotate which lane leads each persisted round.
        sequences = (
            (priority_scores, normal_scores)
            if not progress.get("score_normal_first")
            else (normal_scores, priority_scores)
        )
        for i in range(max(len(normal_scores), len(priority_scores))):
            for sequence in sequences:
                if i < len(sequence) and sequence[i] not in seen:
                    order.append(sequence[i])
                    seen.add(sequence[i])
        visited = set()
        for pair in order:
            if (
                time.monotonic() >= score_deadline
                or store.metadata("PHONE-MARKETS").get("backoff_until", 0) > clock_ms()
            ):
                break
            try:
                market.cached_rally = store.metadata("RALLY-" + pair)
                value = market.rally(
                    pair, universe.get("tickers", {}).get("BTCTRY", {}).get("change", 0)
                )
                ticker = universe.get("tickers", {}).get(pair, {})
                observed = store.read(pair, public=True) or {}
                bars = value.get("chart", {}).get("hour", [])
                value["trade_plan"] = build_plan(
                    pair,
                    bars,
                    ticker.get("price") or (bars[-1]["close"] if bars else None),
                    ticker.get("observed_at")
                    or (bars[-1]["time"] + HOUR if bars else None),
                    value["as_of"],
                    cfg,
                    observed.get("stop_loss"),
                    value.get("analysis", {}).get("spread_pct", 0),
                    entry_research=observed.get("entry_research"),
                )
                score_payload = {**observed, "trade_plan": value["trade_plan"]}
                value["analysis"] = blend_score(
                    value.get("analysis"),
                    score_payload,
                    cfg,
                    pair,
                    value["as_of"],
                    value["as_of"],
                    value.get("error"),
                )
                store.save_rally(pair, value)
            except Exception as exc:
                previous = store.metadata("RALLY-" + pair)
                previous["error"] = str(exc)[:400]
                store.write_metadata("RALLY-" + pair, previous)
                rate_limited(universe, exc, clock_ms())
                results.append(
                    {
                        "pair": pair,
                        "status": "FAILED",
                        "phase": "SCORE",
                        "error": str(exc)[:400],
                    }
                )
            changes = {
                "score_normal_first": not progress.get("score_normal_first", False)
            }
            if pair in visible:
                changes["score_priority_next"] = successor(sorted(visible), pair)
            visited.add(pair)
            while normal_scores and normal_scores[0] in visited:
                changes["score_next"] = successor(cfg.pairs, normal_scores.pop(0))
            store.merge_metadata("PHONE-PROGRESS", changes)
            store.save_backoff(universe.get("backoff_until", 0))
            if universe.get("backoff_until", 0) > clock_ms():
                break
        store.merge_metadata("PHONE-PROGRESS", {"last_score_cycle": clock_ms()})
    if score_only:
        return json.dumps(
            {"as_of": clock_ms(), "mode": "SCORE_ONLY", "results": results},
            allow_nan=False,
        )
    pending = tuple(
        sorted(
            {p["pair"] for p in store.positions() if needs_tracking(p)} & set(cfg.pairs)
        )
    )
    normal = rotated(cfg.pairs, progress.get("research_next"))
    priority = rotated(pending, progress.get("paper_next"))
    order = []
    for i in range(max(len(priority), len(normal))):
        for sequence in (priority, normal):
            if i < len(sequence) and sequence[i] not in order:
                order.append(sequence[i])
    visited = set()
    for pair in order:
        if (
            time.monotonic() >= deadline
            or store.metadata("PHONE-MARKETS").get("backoff_until", 0) > clock_ms()
        ):
            break
        try:
            result = run_pair(
                store,
                market,
                cfg,
                pair,
                f"phone:{pair}:{now // PERIOD}",
                allow_training=bool(charging),
            )
            results.append(result)
            universe["backoff_until"] = max(
                universe.get("backoff_until", 0), getattr(market, "backoff_until", 0)
            )
        except Exception as exc:
            results.append({"pair": pair, "status": "FAILED", "error": str(exc)[:400]})
            rate_limited(universe, exc, clock_ms())
        if pair in pending:
            progress["paper_next"] = successor(pending, pair)
        # Only advance the normal cursor for the next normal pair, so priority
        # entries ahead of it cannot silently skip unvisited coins.
        visited.add(pair)
        while normal and normal[0] in visited:
            progress["research_next"] = successor(cfg.pairs, normal.pop(0))
        store.merge_metadata(
            "PHONE-PROGRESS",
            {k: v for k, v in progress.items() if k in ("paper_next", "research_next")},
        )
    store.save_backoff(universe.get("backoff_until", 0))
    store.heartbeat(clock_ms())
    return json.dumps(
        {
            "as_of": clock_ms(),
            "results": results,
            "numerics": "portable-lbfgs-v1",
            "training_requires_charge": True,
            "coverage": coverage(store, cfg, clock_ms()),
        },
        allow_nan=False,
    )


def rotated(pairs, next_pair):
    pairs = list(pairs)
    start = pairs.index(next_pair) if next_pair in pairs else 0
    return pairs[start:] + pairs[:start]


def successor(pairs, pair):
    return pairs[(pairs.index(pair) + 1) % len(pairs)] if pairs else None


def rate_limited(universe, error, now):
    import urllib.error

    if isinstance(error, urllib.error.HTTPError) and error.code in (418, 429):
        try:
            retry = float(error.headers.get("Retry-After", "60"))
        except (TypeError, ValueError):
            retry = 60
        universe["backoff_until"] = now + int(max(60, retry) * 1000)


def coverage(store, cfg, now):
    scores = [store.metadata("RALLY-" + pair) for pair in cfg.pairs]
    metadata = [store.metadata(pair) for pair in cfg.pairs]
    universe = store.metadata("PHONE-MARKETS")
    return {
        "mode": "ALL_TRY" if cfg.discover_pairs else "SELECTED",
        "total": len(cfg.pairs),
        "scored": sum(
            row.get("analysis", {}).get("score") is not None for row in scores
        ),
        "scores_current": sum(
            row.get("analysis", {}).get("score") is not None
            and now - row.get("as_of", 0) <= 3 * PERIOD
            and not row.get("error")
            for row in scores
        ),
        "researched": sum(bool(row.get("success_at")) for row in metadata),
        "backfill_complete": sum(
            bool(row.get("backfill_complete")) for row in metadata
        ),
        "discovered_at": universe.get("discovered_at"),
        "discovery_error": universe.get("error"),
        "backoff_until": universe.get("backoff_until", 0),
        "last_score_cycle": store.metadata("PHONE-PROGRESS").get("last_score_cycle"),
        **{
            k: v
            for k, v in store.metadata("PHONE-PROGRESS").items()
            if k.endswith("_next")
        },
    }


def status(root, pairs_json):
    return json.dumps(
        coverage(PhoneStore(root), config(pairs_json, root), clock_ms()),
        allow_nan=False,
    )


class PhoneMarket(Market):
    def json(self, base, path, **params):
        import certifi
        import ssl
        import urllib.parse
        import urllib.request
        import urllib.error

        store = getattr(self, "backoff_store", None)
        if (
            store
            and store.metadata("PHONE-MARKETS").get("backoff_until", 0) > clock_ms()
        ):
            raise OSError("Borsa hız sınırı; Retry-After bekleme süresi henüz dolmadı.")
        url = base + path + "?" + urllib.parse.urlencode(params)
        context = ssl.create_default_context(cafile=certifi.where())
        # One bounded attempt; WorkManager handles retries and exponential backoff.
        try:
            with urllib.request.urlopen(url, timeout=12, context=context) as response:
                data = json.load(response)
        except urllib.error.HTTPError as exc:
            backoff = {}
            rate_limited(backoff, exc, clock_ms())
            self.backoff_until = backoff.get("backoff_until", 0)
            if store:
                store.save_backoff(self.backoff_until)
            raise
        if isinstance(data, dict):
            if data.get("code", 0) != 0:
                raise ValueError(f"Binance TR API code {data.get('code')}")
            data = data.get("data", data)
        return data

    def get(self, path, **params):
        data = self.json("https://api.binance.me", path, **params)
        if not isinstance(data, list):
            raise ValueError("Unexpected Binance TR payload")
        return data

    def symbols(self):
        data = self.json("https://www.binance.tr", "/open/v1/common/symbols")
        rows = data.get("list") if isinstance(data, dict) else None
        if not isinstance(rows, list):
            raise ValueError("Unexpected Binance TR symbol list")
        pairs = set()
        for row in rows:
            pair = str(row.get("symbol", "")).replace("_", "")
            if (
                row.get("quoteAsset") == "TRY"
                and row.get("type") in (1, "1")
                and row.get("spotTradingEnable") in (1, "1")
                and re.fullmatch(r"[A-Z0-9]{2,20}TRY", pair)
            ):
                pairs.add(pair)
        return sorted(pairs)

    def tickers(self, now):
        rows = self.get("/api/v1/ticker/24hr")
        result = {}
        for row in rows:
            pair = str(row.get("symbol", "")).replace("_", "")
            if not re.fullmatch(r"[A-Z0-9]{2,20}TRY", pair):
                continue
            price, change, volume = (
                float(row[k])
                for k in ("lastPrice", "priceChangePercent", "quoteVolume")
            )
            if (
                not all(math.isfinite(v) for v in (price, change, volume))
                or price <= 0
                or volume < 0
            ):
                raise ValueError("Invalid Binance TR ticker")
            result[pair] = {
                "price": price,
                "change": change / 100,
                "quote_volume": volume,
                "observed_at": now,
            }
        return result

    def rally(self, pair, btc_change):
        started = clock_ms()
        cached = getattr(self, "cached_rally", {}).get("chart", {})

        def history(name, interval, limit):
            previous = cached.get(name, [])
            if (
                previous
                and previous[-1]["time"] + interval == started // interval * interval
            ):
                return previous
            return validate(
                self.get(
                    "/api/v1/klines",
                    symbol=pair,
                    interval="1h" if interval == HOUR else "1m",
                    limit=limit,
                ),
                clock_ms(),
                interval=interval,
            )

        hour = history("hour", HOUR, 120)
        minute = history("minute", 60_000, 121)
        book = self.json(
            "https://api.binance.me", "/api/v3/depth", symbol=pair, limit=20
        )
        now = clock_ms()
        if (
            not hour
            or not minute
            or hour[-1]["time"] < (now // HOUR - 1) * HOUR
            or minute[-1]["time"] < (now // 60_000 - 1) * 60_000
        ):
            raise ValueError("Rally candles are behind; current score withheld")
        for bars, interval in ((hour, HOUR), (minute, 60_000)):
            if any(
                bars[i + 1]["time"] - bars[i]["time"] != interval
                for i in range(len(bars) - 1)
            ):
                raise ValueError("Rally candle gap; current score withheld")
        analysis = score_market(hour, minute, book, btc_change)
        return {
            "pair": pair,
            "as_of": now,
            "analysis": analysis or {},
            "error": None,
            "chart": {"hour": hour, "minute": minute[-120:], "as_of": now},
        }


def backup(root, pairs_json):
    store, cfg = PhoneStore(root), config(pairs_json, root)
    directory = store.root.parent / "backup-staging"
    directory.mkdir(exist_ok=True)
    database_copy = directory / "research.sqlite3"
    db_file = store.root / "research.sqlite3"
    if db_file.exists():
        with (
            sqlite3.connect(db_file) as source,
            sqlite3.connect(database_copy) as target,
        ):
            source.backup(target)
    temporary, output = (
        directory / (uuid.uuid4().hex + ".tmp"),
        directory / "Oran-Radar-Phone.zip",
    )
    manifest = {
        "schema": 1,
        "created_at": clock_ms(),
        "pairs": list(cfg.pairs),
        "runtime": "PHONE",
        "numerics": "portable-lbfgs-v1",
        "contains_credentials": False,
    }
    with zipfile.ZipFile(
        temporary, "w", zipfile.ZIP_DEFLATED, compresslevel=6
    ) as archive:
        archive.writestr("manifest.json", json.dumps(manifest))
        if db_file.exists():
            archive.write(database_copy, "research.sqlite3")
        for file in [
            *store.root.glob("PHONE-*.json"),
            *store.root.glob("RALLY-*.json"),
        ]:
            archive.write(file, "state/" + file.name)
        known_pairs = {
            file.stem
            for file in store.root.glob("*TRY.json")
            if re.fullmatch(r"[A-Z0-9]{2,20}TRY", file.stem)
        }
        for pair in (*sorted(known_pairs | set(cfg.pairs)), "SCHEDULER"):
            meta = store.metadata(pair)
            archive.writestr(f"state/{pair}.json", json.dumps(meta, allow_nan=False))
            for key in ("key", "public_key"):
                if meta.get(key):
                    archive.write(store.root / meta[key], "state/" + meta[key])
        for file in store.root.glob("PAPER-*.json"):
            archive.write(file, "state/" + file.name)
    os.replace(temporary, output)
    return str(output)
