from statistics import mean, median, pstdev


def report(signals):
    trades = [s for s in signals if s["filled_at"] is not None and s["outcome"] in
              ("SUCCESS", "STOPPED", "TIME_EXPIRED")]
    entries = [s for s in signals if s["outcome"] in
               ("SUCCESS", "STOPPED", "TIME_EXPIRED", "ENTRY_NOT_FILLED")]
    returns = [s["net_return_pct"] for s in trades if s["net_return_pct"] is not None]
    winners = [r for r in returns if r > 0]
    losers = [r for r in returns if r < 0]
    running = peak = drawdown = 0.0
    for r in returns:
        running += r
        peak = max(peak, running)
        drawdown = max(drawdown, peak - running)
    bins = []
    for lo in range(0, 100, 10):
        group = [s for s in trades if s["target_probability"] is not None
                 and lo <= s["target_probability"] * 100 < lo + 10]
        bins.append({"range": f"{lo}–{lo+10}%", "n": len(group),
                     "predicted": round(mean(s["target_probability"] for s in group), 3) if group else None,
                     "observed": round(sum(s["outcome"] == "SUCCESS" for s in group) / len(group), 3)
                     if group else None})
    by_score = []
    for lo in (0, 50, 60, 70, 80, 90):
        hi = 50 if lo == 0 else min(100, lo + 10)
        group = [s for s in trades if lo <= s["rally_score"] < hi + (hi == 100)]
        by_score.append({"range": f"{lo}–{hi}", "n": len(group),
                         "success_rate": round(sum(s["outcome"] == "SUCCESS" for s in group) /
                                               len(group), 3) if group else None})
    return {
        "model_status": "PROVISIONAL_UNCALIBRATED",
        "independent_forward_trades": len(trades),
        "total_signals": len(signals),
        "entry_observed_rate": round(len(trades) / len(entries), 3) if entries else None,
        "entry_not_filled": sum(s["outcome"] == "ENTRY_NOT_FILLED" for s in signals),
        "cancelled": sum(s["outcome"] == "CANCELLED" for s in signals),
        "success_after_entry": round(sum(s["outcome"] == "SUCCESS" for s in trades) /
                                     len(trades), 3) if trades else None,
        "full_success_rate": round(sum(s["outcome"] == "SUCCESS" for s in signals) /
                                   len(entries), 3) if entries else None,
        "stop_rate": round(sum(s["outcome"] == "STOPPED" for s in trades) /
                           len(trades), 3) if trades else None,
        "time_expired_rate": round(sum(s["outcome"] == "TIME_EXPIRED" for s in trades) /
                                   len(trades), 3) if trades else None,
        "mean_net_pct": round(mean(returns), 3) if returns else None,
        "median_net_pct": round(median(returns), 3) if returns else None,
        "profit_factor": round(sum(winners) / -sum(losers), 3) if losers else None,
        "expectancy_pct": round(mean(returns), 3) if returns else None,
        "max_drawdown_pct_points": round(drawdown, 3) if returns else None,
        "mean_over_std": round(mean(returns) / pstdev(returns), 3)
        if len(returns) > 1 and pstdev(returns) else None,
        "average_trade_hours": round(mean((s["closed_at"] - s["filled_at"]) / 3600_000
                                          for s in trades), 2) if trades else None,
        "target_within": {str(h): round(sum(s["outcome"] == "SUCCESS" and
                                            s["closed_at"] - s["filled_at"] <= h * 3600_000
                                            for s in trades) / len(trades), 3) if trades else None
                          for h in (6, 12, 24, 48)},
        "calibration": bins,
        "by_rally_score": by_score,
        "caveat": "Observed price touch is not a confirmed order fill. Portfolio drawdown "
                  "and Sharpe need position sizing and equity chronology; "
                  "max_drawdown_pct_points is sum of sequential paper trade returns."
    }
