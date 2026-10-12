"""NumPy/stdlib numerical routines for Android, without a SciPy native dependency.

Normal tails use erfc; Student-t tails use the regularized incomplete beta;
Fisher's two-sided test enumerates the fixed-margin hypergeometric support.
Unconstrained ridge logistic fitting uses L-BFGS with Armijo line search.
"""

import math
from functools import lru_cache
from statistics import NormalDist
from types import SimpleNamespace
import numpy as np


class Normal:
    @staticmethod
    def ppf(p):
        return NormalDist().inv_cdf(float(p))

    @staticmethod
    def sf(x):
        return math.erfc(float(x) / math.sqrt(2)) / 2


norm = Normal()


def _beta_fraction(a, b, x):
    qab, qap, qam = a + b, a + 1, a - 1
    tiny = 1e-300
    c, d = 1.0, 1 - qab * x / qap
    d = 1 / (d if abs(d) > tiny else tiny)
    h = d
    for m in range(1, 401):
        aa = m * (b - m) * x / ((qam + 2 * m) * (a + 2 * m))
        d = 1 + aa * d
        c = 1 + aa / c
        d, c = (d if abs(d) > tiny else tiny), (c if abs(c) > tiny else tiny)
        d = 1 / d
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + 2 * m) * (qap + 2 * m))
        d = 1 + aa * d
        c = 1 + aa / c
        d, c = (d if abs(d) > tiny else tiny), (c if abs(c) > tiny else tiny)
        d = 1 / d
        change = d * c
        h *= change
        if abs(change - 1) < 3e-14:
            return h
    raise ValueError("Student-t beta fraction did not converge")


def _beta(a, b, x):
    if x <= 0:
        return 0.0
    if x >= 1:
        return 1.0
    weight = math.exp(
        math.lgamma(a + b)
        - math.lgamma(a)
        - math.lgamma(b)
        + a * math.log(x)
        + b * math.log1p(-x)
    )
    if x < (a + 1) / (a + b + 2):
        return weight * _beta_fraction(a, b, x) / a
    return 1 - weight * _beta_fraction(b, a, 1 - x) / b


class Student:
    @staticmethod
    def sf(x, df):
        if df <= 0:
            raise ValueError("Degrees of freedom must be positive")
        tail = _beta(df / 2, 0.5, df / (df + x * x)) / 2
        return tail if x >= 0 else 1 - tail

    @staticmethod
    def ppf(p, df):
        if not 0 < p < 1 or df <= 0:
            raise ValueError("Invalid Student-t quantile")
        if p == 0.5:
            return 0.0
        tail = min(p, 1 - p)
        lo, hi = 0.0, 1.0
        while Student.sf(hi, df) > tail:
            hi *= 2
            if hi > 1e20:
                raise ValueError("Student-t quantile out of range")
        for _ in range(85):
            mid = (lo + hi) / 2
            if Student.sf(mid, df) > tail:
                lo = mid
            else:
                hi = mid
        value = (lo + hi) / 2
        return value if p > 0.5 else -value


student_t = Student()


@lru_cache(maxsize=16)
def _log_factorials(n):
    return np.concatenate(([0.0], np.cumsum(np.log(np.arange(1, n + 1)))))


def fisher_exact(table):
    cells = np.asarray(table)
    if cells.shape != (2, 2) or (cells < 0).any() or (cells != np.floor(cells)).any():
        raise ValueError("Fisher table must contain nonnegative integer counts")
    a, b, c, d = (int(x) for x in cells.flat)
    total, row, col = a + b + c + d, a + b, a + c
    if total == 0 or min(row, total - row, col, total - col) == 0:
        return SimpleNamespace(pvalue=1.0, statistic=math.nan)
    logs = _log_factorials(total)
    support = np.arange(max(0, row + col - total), min(row, col) + 1, dtype=int)
    probabilities = (
        logs[col]
        - logs[support]
        - logs[col - support]
        + logs[total - col]
        - logs[row - support]
        - logs[total - col - row + support]
        - logs[total]
        + logs[row]
        + logs[total - row]
    )
    observed = probabilities[a - support[0]]
    p = float(np.exp(probabilities[probabilities <= observed + 1e-10]).sum())
    odds = a * d / (b * c) if b * c else math.inf
    return SimpleNamespace(pvalue=min(1.0, p), statistic=odds)


def expit(x):
    x = np.asarray(x, dtype=float)
    return np.exp(-np.logaddexp(0, -x))


def minimize(fun, initial, jac=True, method="L-BFGS-B", options=None):
    if jac is not True or method != "L-BFGS-B":
        raise ValueError(
            "Portable optimizer accepts only unconstrained analytic-gradient fits"
        )
    options = options or {}
    x = np.array(initial, dtype=float)
    value, gradient = fun(x)
    history = []
    for iteration in range(options.get("maxiter", 500)):
        if not np.isfinite(value) or not np.isfinite(gradient).all():
            break
        if np.max(np.abs(gradient)) <= options.get("gtol", 1e-4):
            return SimpleNamespace(success=True, x=x, fun=value, nit=iteration)
        q, coefficients = gradient.copy(), []
        for s, y, rho in reversed(history):
            alpha = rho * float(s @ q)
            coefficients.append(alpha)
            q -= alpha * y
        scale = (
            float(history[-1][0] @ history[-1][1])
            / float(history[-1][1] @ history[-1][1])
            if history
            else 1.0
        )
        direction = q * scale
        for (s, y, rho), alpha in zip(history, reversed(coefficients)):
            direction += s * (alpha - rho * float(y @ direction))
        direction = -direction
        slope = float(gradient @ direction)
        if slope >= 0:
            direction, slope = -gradient, -float(gradient @ gradient)
        step = (
            min(1.0, 1 / max(1.0, float(np.linalg.norm(gradient))))
            if not history
            else 1.0
        )
        accepted = False
        for _ in range(40):
            next_x = x + step * direction
            next_value, next_gradient = fun(next_x)
            if np.isfinite(next_value) and next_value <= value + 1e-4 * step * slope:
                accepted = True
                break
            step /= 2
        if not accepted:
            break
        s, y = next_x - x, next_gradient - gradient
        curvature = float(s @ y)
        if curvature > 1e-12:
            history.append((s, y, 1 / curvature))
            history = history[-10:]
        converged = abs(value - next_value) <= options.get("ftol", 1e-8) * max(
            1, abs(value), abs(next_value)
        )
        x, value, gradient = next_x, next_value, next_gradient
        if converged:
            return SimpleNamespace(success=True, x=x, fun=value, nit=iteration + 1)
    return SimpleNamespace(
        success=False, x=x, fun=value, nit=options.get("maxiter", 500)
    )
