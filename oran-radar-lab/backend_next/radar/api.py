"""Shared local/Lambda API, including truthful audit and asynchronous recovery."""

import hmac
import json
from urllib.parse import parse_qs
from .config import HOUR, PERIOD, VERSION
from .paper import needs_tracking, new_position
from .stops import public_stops


def audit(store, cfg, now):
    issues, pairs = [], []
    for pair in cfg.pairs:
        meta = store.metadata(pair)
        pair_issues = []
        if not meta.get("success_at") or now - meta["success_at"] > 3 * PERIOD:
            pair_issues.append("WORKER_STALE")
        if (
            not meta.get("last_closed_at")
            or meta["last_closed_at"] < (now // HOUR - 1) * HOUR
        ):
            pair_issues.append("CANDLES_BEHIND")
        if not meta.get("backfill_complete"):
            pair_issues.append("BACKFILL_INCOMPLETE")
        if meta.get("error"):
            pair_issues.append("LAST_JOB_FAILED")
        if meta.get("lease_until", 0) <= now and meta.get("stage") == "STARTED":
            pair_issues.append("EXPIRED_LEASE")
        if (
            not meta.get("trained_at")
            or now - meta["trained_at"] > cfg.retrain_ms + 3 * PERIOD
        ):
            pair_issues.append("RESEARCH_DUE")
        if not meta.get("stop_trained_at"):
            pair_issues.append("STOP_RESEARCH_MISSING")
        elif now - meta["stop_trained_at"] > cfg.retrain_ms + 3 * PERIOD:
            pair_issues.append("STOP_RESEARCH_DUE")
        if not meta.get("entry_trained_at"):
            pair_issues.append("ENTRY_RESEARCH_MISSING")
        elif now - meta["entry_trained_at"] > cfg.retrain_ms + 3 * PERIOD:
            pair_issues.append("ENTRY_RESEARCH_DUE")
        for stage, status in meta.get("stages", {}).items():
            if status != "OK":
                pair_issues.append(f"{stage}:{status}")
        value = {
            **meta,
            "pair": pair,
            "issues": sorted(set(pair_issues)),
            "running": meta.get("lease_until", 0) > now,
        }
        # Never return object keys, lease tokens or infrastructure internals to UI.
        for key in ("key", "public_key", "lease", "pk"):
            value.pop(key, None)
        pairs.append(value)
        issues.extend({"pair": pair, "code": code} for code in value["issues"])
    for position in store.positions():
        if needs_tracking(position) and position.get("feed_blocked"):
            issues.append(
                {
                    "pair": position["pair"],
                    "code": "PAPER_TRADE_GAP",
                    "position_id": position["position_id"],
                }
            )
    scheduler = store.metadata("SCHEDULER")
    if not scheduler.get("success_at") or now - scheduler["success_at"] > 3 * PERIOD:
        issues.append({"pair": None, "code": "SCHEDULER_STALE"})
    return {
        "ok": not issues,
        "checked_at": now,
        "issues": issues,
        "pairs": pairs,
        "scheduler_at": scheduler.get("success_at"),
        "scope": "configured pairs, closed candles, pipeline stages, stop research/shadow trials, paper/post-stop trade continuity and scheduler",
    }


class API:
    def __init__(self, store, cfg, enqueue):
        self.store, self.cfg, self.enqueue = store, cfg, enqueue

    def handle(self, method, path, query, headers, body, now):
        public = method == "GET" and path == "/health"
        authorization = next(
            (v for k, v in headers.items() if k.lower() == "authorization"), ""
        )
        if (
            not public
            and self.cfg.token
            and not hmac.compare_digest(authorization, "Bearer " + self.cfg.token)
        ):
            return 401, {"error": "Erişim anahtarı gerekli."}
        if path == "/health" and method == "GET":
            return 200, {
                "mode": "PAPER_ONLY",
                "model": VERSION,
                "research_backend": True,
                "runtime": self.store.runtime,
                "paper_tracking": True,
                "auth_required": bool(self.cfg.token),
                "capabilities": [
                    "research",
                    "snapshot",
                    "audit-recover",
                    "paper-trade-backfill",
                    "stop-loss-research",
                    "limit-entry-research",
                    "stop-loss-shadow-trials",
                    "post-stop-trade-followup",
                ],
                "now": now,
            }
        if path == "/v2/audit" and method == "GET":
            return 200, audit(self.store, self.cfg, now)
        if path == "/v2/control/reconcile" and method == "POST":
            before = audit(self.store, self.cfg, now)
            targets = sorted(
                {issue["pair"] for issue in before["issues"] if issue["pair"]}
            )
            if any(i["code"] == "SCHEDULER_STALE" for i in before["issues"]):
                targets = list(self.cfg.pairs)
            queued = []
            for pair in targets:
                self.enqueue(pair, f"recover:{pair}:{now // PERIOD}", True)
                queued.append(pair)
            # Queue acceptance does not mean recovery has completed.
            return 202, {
                "audit": before,
                "queued": queued,
                "status": "RECOVERY_QUEUED" if queued else "HEALTHY",
                "message": "Eksik işler kuyruğa alındı; tamamlanınca kontrol yeşile dönecek."
                if queued
                else "Tüm kontrol edilen işlemler güncel.",
            }
        if path == "/v2/snapshot" and method == "GET":
            check = audit(self.store, self.cfg, now)
            items = []
            for pair in self.cfg.pairs:
                payload = self.store.read(pair, public=True)
                if payload:
                    payload["stale"] = (
                        now - payload["as_of"] > 3 * PERIOD
                        or not payload["backfill_complete"]
                        or not payload.get("observed_at")
                        or payload["observed_at"] < now // HOUR * HOUR
                    )
                    payload["issues"] = next(
                        p["issues"] for p in check["pairs"] if p["pair"] == pair
                    )
                    items.append(payload)
            return 200, {
                "schema_version": 2,
                "runtime": self.store.runtime,
                "as_of": now,
                "audit": check,
                "items": items,
                "paper_positions": self.store.positions(),
                "expected_pairs": list(self.cfg.pairs),
                "disclaimer": "İstatistiksel araştırma; kesin getiri veya nedensellik iddiası yoktur.",
            }
        params = parse_qs(query)
        if (
            path in ("/v2/stop-loss", "/v2/stop-loss/research", "/v2/stop-loss/forward")
            and method != "GET"
        ):
            return 405, {"error": "Use GET for stop research"}
        pair = params.get("pair", [""])[0]
        if path in (
            "/v2/research",
            "/v2/forward",
            "/market",
            "/candles",
            "/v2/stop-loss",
            "/v2/stop-loss/research",
            "/v2/stop-loss/forward",
        ):
            if pair not in self.cfg.pairs:
                return 404, {"error": "Configured TRY pair not found"}
            state = self.store.read(pair)
            if not state:
                return 503, {"error": "İlk veri toplama henüz tamamlanmadı."}
            if path == "/v2/stop-loss" and method == "GET":
                return 200, public_stops(state)
            if (
                path in ("/v2/stop-loss/research", "/v2/stop-loss/forward")
                and method == "GET"
            ):
                try:
                    offset = max(0, int(params.get("offset", ["0"])[0]))
                except ValueError:
                    return 400, {"error": "offset must be an integer"}
                rows = (
                    state.get("stop_research", {}).get("evaluations", [])
                    if path.endswith("research")
                    else state.get("stop_sessions", [])
                )
                return 200, {
                    "items": rows[offset : offset + 100],
                    "total": len(rows),
                    "next_offset": offset + 100 if offset + 100 < len(rows) else None,
                }
            if path == "/v2/research":
                offset = max(0, int(params.get("offset", ["0"])[0]))
                rows = state.get("research", {}).get("hypotheses", [])
                return 200, {
                    "items": rows[offset : offset + 100],
                    "total": len(rows),
                    "next_offset": offset + 100 if offset + 100 < len(rows) else None,
                }
            if path == "/v2/forward":
                offset = max(0, int(params.get("offset", ["0"])[0]))
                rows = state.get("forward", [])
                return 200, {
                    "items": rows[offset : offset + 100],
                    "total": len(rows),
                    "next_offset": offset + 100 if offset + 100 < len(rows) else None,
                }
            if path == "/candles":
                return 200, {
                    "minute": [],
                    "hour": state["bars"][-120:],
                    "as_of": state["completed_at"],
                }
            return 200, {
                "pair": pair,
                "opportunity": None,
                "research": self.store.read(pair, public=True),
            }
        if path == "/pairs" and method == "GET":
            return 200, [
                {
                    "pair": p,
                    "display": p[:-3] + "/TRY",
                    "price": (self.store.read(p, public=True) or {}).get("price"),
                    "observed_at": (self.store.read(p, public=True) or {}).get(
                        "observed_at"
                    ),
                }
                for p in self.cfg.pairs
            ]
        if path == "/paper-positions" and method == "GET":
            return 200, self.store.positions()
        if path == "/paper-positions" and method == "POST":
            if len(body.encode()) > 4096:
                return 413, {"error": "Request too large"}
            try:
                position = new_position(json.loads(body), now, self.cfg.pairs)
                observed = self.store.read(position["pair"], public=True) or {}
                if hasattr(self.store, "paper_market"):
                    observed = self.store.paper_market(position["pair"], now)
                timestamp = observed.get("observed_at")
                causal = timestamp is not None and timestamp <= now
                position["market_context"] = {
                    "source": observed.get("source"),
                    "observed_at": timestamp,
                    "model_version": observed.get("version"),
                    "stale": not timestamp or timestamp > now or now - timestamp > HOUR,
                    "features": {
                        k: v
                        for k, v in observed.get("features", {}).items()
                        if k.endswith("@lag0")
                    }
                    if causal
                    else {},
                    "stop_context": observed.get("stop_loss", {}).get("context", {})
                    if causal
                    else {},
                    "proposed_plan": observed.get("trade_plan")
                    if (observed.get("trade_plan") or {}).get("as_of", now + 1) <= now
                    else None,
                }
                saved = self.store.create_position(position)
                self.enqueue(saved["pair"], f"paper:{saved['position_id']}", False)
                return 201, saved
            except (KeyError, TypeError, ValueError) as exc:
                return 400, {"error": str(exc)}
        # Preserve legacy UI expectations; research probabilities never impersonate trade signals.
        if path in ("/opportunities", "/signals", "/history") and method == "GET":
            return 200, []
        if path == "/metrics" and method == "GET":
            return 200, {"research_backend": True}
        if path == "/devices":
            return 501, {
                "error": "Bu bağımsız sürümde push teslimatı yapılandırılmadı."
            }
        return 404, {"error": "Route not found"}
