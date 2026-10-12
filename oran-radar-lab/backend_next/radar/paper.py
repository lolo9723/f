"""Public trade evidence, contiguous cursors and conservative paper outcomes."""

import math
import uuid
from .config import HOUR


def new_position(body, now, pairs, fee_bps=20):
    sid = str(uuid.UUID(body["position_id"]))
    pair = body["pair"]
    entry, target = float(body["entry"]), float(body["target"])
    stop = None if body.get("stop") is None else float(body["stop"])
    if (
        pair not in pairs
        or not all(math.isfinite(v) for v in (entry, target))
        or entry <= 0
        or target <= entry
        or (stop is not None and (not math.isfinite(stop) or not 0 < stop < entry))
    ):
        raise ValueError("Invalid TRY pair or paper levels")
    return {
        "position_id": sid,
        "pair": pair,
        "entry": entry,
        "target": target,
        "stop": stop,
        "created_at": now,
        "deadline_at": now + 48 * HOUR,
        "state": "TRACKING",
        "closed_at": None,
        "target_hit_at": None,
        "observed_price": None,
        "observed_at": None,
        "last_trade_id": None,
        "gross_return_pct": None,
        "net_return_pct": None,
        "fee_bps": fee_bps,
        "stop_distance_pct": (entry - stop) / entry * 100 if stop is not None else None,
        "risk_reward": (target - entry) / (entry - stop) if stop is not None else None,
        "mae_pct": 0,
        "mfe_pct": 0,
        "exit_price": None,
        "stop_slippage_pct": None,
        "followup_state": "WAITING_STOP" if stop is not None else "NOT_NEEDED",
        "recovered_at": None,
        "post_stop_mfe_pct": None,
        "feed_blocked": True,
        "origin": "server",
        "revision": 0,
        "search_at": now,
    }


def needs_tracking(position):
    return position["state"] == "TRACKING" or (
        position["state"] == "STOPPED"
        and position.get("stop") is not None
        and position.get("followup_state", "PENDING") == "PENDING"
    )


def apply(position, trade):
    if not needs_tracking(position) or trade["time"] < position["created_at"]:
        return
    p, t = trade["price"], trade["time"]
    position.update(last_trade_id=trade["id"], observed_at=t, observed_price=p)
    move = (p / position["entry"] - 1) * 100
    if position["state"] == "STOPPED":
        position["followup_state"] = "PENDING"
        if t >= position["deadline_at"]:
            position.update(followup_state="COMPLETED", followup_completed_at=t)
        else:
            previous = position.get("post_stop_mfe_pct")
            position["post_stop_mfe_pct"] = (
                move if previous is None else max(previous, move)
            )
            if p >= position["target"]:
                position.update(
                    followup_state="RECOVERED_TO_TARGET",
                    recovered_at=t,
                    followup_completed_at=t,
                )
        return
    position["mae_pct"] = min(position.get("mae_pct", 0), move)
    position["mfe_pct"] = max(position.get("mfe_pct", 0), move)
    # Deadline wins: a target reached after the tracking window is not a success.
    outcome = (
        "TIMEOUT"
        if t >= position["deadline_at"]
        else "STOPPED"
        if position["stop"] is not None and p <= position["stop"]
        else "TARGET_HIT"
        if p >= position["target"]
        else None
    )
    if outcome:
        gross = (p / position["entry"] - 1) * 100
        position.update(
            state=outcome,
            closed_at=t,
            target_hit_at=t if outcome == "TARGET_HIT" else None,
            gross_return_pct=gross,
            net_return_pct=gross - position["fee_bps"] / 100,
            exit_price=p,
            stop_slippage_pct=max(0, (position["stop"] - p) / position["entry"] * 100)
            if outcome == "STOPPED"
            else None,
            followup_state="PENDING" if outcome == "STOPPED" else "NOT_NEEDED",
        )


def track_pair(store, market, pair, now, max_pages=5):
    positions = [
        p for p in store.positions() if p["pair"] == pair and needs_tracking(p)
    ]
    backlog = False
    budget = max_pages
    positions.sort(key=lambda p: p.get("observed_at") or p["created_at"])
    for position in positions:
        if budget <= 0:
            backlog = True
            continue
        expected_revision = position["revision"]
        position["feed_blocked"] = True
        try:
            for _ in range(budget):
                cursor = position["last_trade_id"]
                start = position.get("search_at", position["created_at"])
                end = min(now, start + 3_599_000)
                budget -= 1
                initial = cursor is None
                batch = market.trades(pair, cursor, start, end)
                if not batch:
                    if cursor is None and end < now:
                        position["search_at"] = end + 1
                        continue
                    position["feed_blocked"] = False
                    break
                for raw in batch:
                    trade = {
                        "id": int(raw["a"]),
                        "time": int(raw["T"]),
                        "price": float(raw["p"]),
                    }
                    if (
                        not math.isfinite(trade["price"])
                        or trade["price"] <= 0
                        or trade["time"] > now
                    ):
                        raise ValueError("Invalid trade price or timestamp")
                    if initial and not start <= trade["time"] <= end:
                        raise ValueError("Source ignored requested trade time window")
                    cursor = position["last_trade_id"]
                    if cursor is not None and trade["id"] != cursor + 1:
                        raise ValueError("Trade ID gap; outcome withheld")
                    if trade["time"] < position["created_at"] or (
                        position["observed_at"]
                        and trade["time"] < position["observed_at"]
                    ):
                        raise ValueError("Trade timestamp gap; outcome withheld")
                    apply(position, trade)
                    if not needs_tracking(position):
                        position["feed_blocked"] = False
                        break
                if not needs_tracking(position):
                    break
                if len(batch) < 1000 and (cursor is not None or end >= now):
                    position["feed_blocked"] = False
                    break
            backlog |= position["feed_blocked"]
            if (
                not position["feed_blocked"]
                and position["state"] == "STOPPED"
                and position.get("followup_state", "PENDING") == "PENDING"
                and now >= position["deadline_at"]
            ):
                position.update(followup_state="COMPLETED", followup_completed_at=now)
            store.save_position(position, expected_revision)
        except Exception as exc:
            # Do not commit partial target/stop claims from an invalid trade batch.
            original = next(
                p
                for p in store.positions()
                if p["position_id"] == position["position_id"]
            )
            original.update(feed_blocked=True, tracking_error=str(exc)[:300])
            store.save_position(original, expected_revision)
            backlog = True
    return "BACKFILLING" if backlog else "OK"
