"""Transparent, deliberately provisional models; never label estimates calibrated."""
import math
from statistics import mean, pstdev

from market import align_price


def clamp(x, lo=0, hi=1):
    return max(lo, min(hi, x))


def estimate(successes, trials):
    # Jeffreys smoothing. Independent horizon windows, no duplicated rolling labels.
    return round((successes + .5) / (trials + 1), 3) if trials else None


def score_market(hourly, minute, book, btc_change=0):
    if len(hourly) < 80 or len(minute) < 60:
        return None
    closes = [x["close"] for x in hourly]
    volumes = [x["quote_volume"] for x in minute]
    price = closes[-1]
    trend = clamp(.5 + (price / mean(closes[-24:]) - 1) * 22)
    early = clamp(.7 - max(0, price / max(closes[-48:]) - 1) * 12)
    qvol = mean(volumes[-5:]) / max(1, mean(volumes[-60:-5]))
    volume = clamp((qvol - .6) / 2.2)
    ranges = [(x["high"] - x["low"]) / max(x["close"], 1e-12) for x in hourly]
    compression = clamp(1 - mean(ranges[-8:]) / max(1e-9, mean(ranges[-72:-8])))
    bid, ask = book["bids"], book["asks"]
    spread = (float(ask[0][0]) - float(bid[0][0])) / price
    bsum = sum(float(p) * float(q) for p, q in bid[:10])
    asum = sum(float(p) * float(q) for p, q in ask[:10])
    imbalance = bsum / max(bsum + asum, 1)
    flow = clamp(.5 + (imbalance - .5) * 1.6)
    breadth = clamp(.5 + btc_change * 15)
    score = round(100 * (.25 * trend + .17 * early + .21 * volume +
                         .14 * compression + .16 * flow + .07 * breadth))
    return {"score": score, "spread_pct": round(spread * 100, 4),
            "trend": round(trend, 3), "volume_ratio": round(qvol, 3),
            "compression": round(compression, 3), "book_imbalance": round(imbalance, 3),
            "btc_change": round(btc_change, 4), "price": price}


def scenarios(hourly, minute, current, tick, spread_pct, fee_bps, exit_bps, slip_bps):
    """A horizon-separated historical baseline, not a trained or validated classifier."""
    result = []
    if len(hourly) < 480:  # less than ~20 days cannot justify a 48h estimate
        return result
    ranges = [(x["high"] - x["low"]) / max(x["close"], 1e-12) for x in hourly[-200:]]
    typical = mean(ranges)
    discounts = sorted({round(clamp(typical * f, .002, .025), 5) for f in [.3, .6, 1.0, 1.5]})
    for discount in discounts:
        entry = align_price(current * (1 - discount), tick)
        if entry <= 0 or entry >= current:
            continue
        hits = {h: 0 for h in (2, 6, 12, 24)}
        counts = {h: 0 for h in (2, 6, 12, 24)}
        entry_durations = []
        # Anchors 24h apart ensure samples within each entry horizon do not overlap.
        for i in range(0, len(hourly) - 25, 24):
            anchor = hourly[i]["close"]
            level = anchor * (entry / current)
            first_touch = next((offset for offset, candle in
                                enumerate(hourly[i + 1:i + 25], 1)
                                if candle["low"] <= level), None)
            if first_touch is not None:
                entry_durations.append(first_touch)
            for h in hits:
                counts[h] += 1
                hits[h] += int(first_touch is not None and first_touch <= h)
        entry_prob = {str(h): estimate(hits[h], counts[h]) for h in hits}
        for gain in (.01, .015, .02, .025, .03, .04, .05):
            target = align_price(entry * (1 + gain), tick, "up")
            stop = align_price(entry * (1 - max(.008, 1.3 * typical)), tick)
            if stop <= 0 or target <= entry or stop >= entry:
                continue
            buckets = {h: 0 for h in (6, 12, 24, 48)}
            filled = won = lost = timed = 0
            adverse = favorable = duration = 0.0
            # 56h independent blocks: first 8h for entry, then 48h for exit.
            for i in range(0, len(hourly) - 57, 56):
                anchor = hourly[i]["close"]
                e = anchor * entry / current
                first = next((j for j in range(i + 1, i + 9) if hourly[j]["low"] <= e), None)
                if first is None:
                    continue
                filled += 1
                t, s = e * target / entry, e * stop / entry
                hi, lo, verdict = e, e, "time"
                for k, bar in enumerate(hourly[first + 1:first + 49], 1):
                    hi, lo = max(hi, bar["high"]), min(lo, bar["low"])
                    # If both levels are in one hourly candle, assume stop came first.
                    if bar["low"] <= s:
                        verdict, duration = "stop", duration + k
                        break
                    if bar["high"] >= t:
                        verdict, duration = "target", duration + k
                        for h in buckets:
                            buckets[h] += int(k <= h)
                        break
                adverse += (e - lo) / e
                favorable += (hi - e) / e
                won += verdict == "target"
                lost += verdict == "stop"
                timed += verdict == "time"
            # Prevent precision theater with 5 independent examples.
            if filled < 12:
                target_p = stop_p = None
                within = {str(h): None for h in buckets}
            else:
                target_p = estimate(won, filled)
                stop_p = estimate(lost, filled)
                within = {str(h): estimate(v, filled) for h, v in buckets.items()}
            gross_pct = (target / entry - 1) * 100
            friction_pct = (fee_bps + exit_bps + slip_bps) / 100 + spread_pct / 2
            net_pct = gross_pct - friction_pct
            loss_pct = (stop / entry - 1) * 100 - friction_pct
            expected_pct = (target_p * net_pct + stop_p * loss_pct +
                            max(0, 1 - target_p - stop_p) * (-friction_pct)
                            if target_p is not None else None)
            result.append({
                "entry": entry, "target": target, "stop": stop,
                "discount_pct": round((1 - entry / current) * 100, 3),
                "estimated_fill_hours_if_touched": round(mean(entry_durations), 2)
                if entry_durations else None,
                "entry_probabilities": entry_prob,
                "target_probabilities": within,
                "target_before_stop": target_p, "stop_before_target": stop_p,
                "historical_entry_samples": counts[24], "historical_trade_samples": filled,
                "expected_mae_pct": round(adverse / filled * 100, 3) if filled else None,
                "expected_mfe_pct": round(favorable / filled * 100, 3) if filled else None,
                "avg_resolved_hours": round(duration / (won + lost), 2) if won + lost else None,
                "net_target_pct": round(net_pct, 3),
                "expected_net_pct": round(expected_pct, 3) if expected_pct is not None else None,
            })
    def utility(s):
        p_fill = s["entry_probabilities"]["6"] or 0
        expected = s["expected_net_pct"]
        if expected is None:
            return -999
        # Penalize distant limits that never fill, even when conditional returns look good.
        return p_fill * expected - .08 * (1 - p_fill)
    return sorted(result, key=utility, reverse=True)


def state_for(score, best, spread_pct):
    if score < 55:
        return "WATCH"
    if score < 70:
        return "SCOUT"
    if score < 82 or not best:
        return "SETUP"
    # Provisional empirical evidence, strict enough to prevent arbitrary entries.
    if (spread_pct <= .35 and best["historical_trade_samples"] >= 12 and
        best["entry_probabilities"]["6"] is not None and
        best["entry_probabilities"]["6"] >= .40 and
        best["target_before_stop"] is not None and best["target_before_stop"] >= .55 and
        best["expected_net_pct"] is not None and best["expected_net_pct"] > .20):
        return "ENTRY"
    return "SETUP"
