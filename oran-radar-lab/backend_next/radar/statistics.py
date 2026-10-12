"""Predeclared power, Wilson uncertainty, dependent-sample discount and Holm FWER."""

import math
import numpy as np
from .science import norm


def wilson(success, total, alpha=0.05):
    if total <= 0:
        return [None, None]
    z = norm.ppf(1 - alpha / 2)
    p = success / total
    denominator = 1 + z * z / total
    center = (p + z * z / (2 * total)) / denominator
    radius = (
        z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total)) / denominator
    )
    return [max(0.0, center - radius), min(1.0, center + radius)]


def required_per_group(alpha=0.05, power=0.8, delta=0.1):
    # Conservative p=.5, equal sized two-sided two-proportion comparison.
    # A design target, not post-hoc "observed power".
    z = norm.ppf(1 - alpha / 2) + norm.ppf(power)
    return math.ceil(2 * 0.25 * z * z / (delta * delta))


def precision_n(half_width=0.1, alpha=0.05):
    return math.ceil(norm.ppf(1 - alpha / 2) ** 2 * 0.25 / (half_width * half_width))


def effective_n(values):
    a = np.asarray(values, dtype=float)
    n = len(a)
    if n < 3 or np.std(a) < 1e-12:
        return float(n)
    # Conservative positive-sequence autocorrelation discount; never inflate n.
    total = 0.0
    for lag in range(1, min(n // 4, 25)):
        if min(np.std(a[:-lag]), np.std(a[lag:])) < 1e-12:
            break
        rho = float(np.corrcoef(a[:-lag], a[lag:])[0, 1])
        if not np.isfinite(rho) or rho <= 0:
            break
        total += rho
    return max(1.0, min(float(n), n / (1 + 2 * total)))


def holm(pvalues):
    order = np.argsort(pvalues)
    adjusted = [1.0] * len(pvalues)
    last = 0.0
    for rank, idx in enumerate(order):
        last = max(last, min(1.0, float(pvalues[idx]) * (len(pvalues) - rank)))
        adjusted[int(idx)] = last
    return adjusted


def alpha_budget(round_no, alpha=0.05):
    # Sum alpha/[r(r+1)] over r>=1 equals alpha; no unlimited repeated peeking.
    return alpha / (round_no * (round_no + 1))


def calibration(y, p):
    y, p = np.array(y, dtype=float), np.array(p, dtype=float)
    if not len(y):
        return {"n": 0, "brier": None, "ece": None, "bins": []}
    bins, ece = [], 0.0
    for lo in np.arange(0.0, 1.0, 0.2):
        m = (p >= lo) & (p < lo + 0.2 + 1e-12 if lo > 0.79 else p < lo + 0.2)
        if m.any():
            predicted, observed = float(p[m].mean()), float(y[m].mean())
            ece += m.sum() / len(y) * abs(predicted - observed)
            bins.append(
                {
                    "range": f"{lo:.1f}–{lo + 0.2:.1f}",
                    "n": int(m.sum()),
                    "predicted": predicted,
                    "observed": observed,
                    "interval": wilson(float(y[m].sum()), int(m.sum())),
                }
            )
    return {
        "n": len(y),
        "brier": float(np.mean((p - y) ** 2)),
        "ece": float(ece),
        "bins": bins,
    }
