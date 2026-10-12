"""Chronological discovery/selection/test. Test rows never select features or penalties."""

import hashlib
import json
import math
import numpy as np
from .science import minimize, expit, fisher_exact, norm

from .config import score_direction_design as direction_design
from .features import NAMES, dataset
from .statistics import (
    alpha_budget,
    calibration,
    effective_n,
    holm,
    precision_n,
    required_per_group,
    wilson,
)


def expand(x, names, interactions):
    extra = [
        np.clip(x[:, a], -10, 10) * np.clip(x[:, b], -10, 10) for a, b in interactions
    ]
    return np.column_stack([x, *extra]) if extra else x


def predict(model, x):
    x = np.atleast_2d(x)
    z = np.clip((x - np.array(model["mean"])) / np.array(model["scale"]), -10, 10)
    z = expand(z, NAMES, model["interactions"])
    return expit(np.column_stack([np.ones(len(z)), z]) @ np.array(model["weights"]))


def fit(x, y, times, interactions, penalty):
    mu, scale = np.mean(x, axis=0), np.std(x, axis=0)
    scale[scale < 1e-12] = 1
    z = expand(np.clip((x - mu) / scale, -10, 10), NAMES, interactions)
    design = np.column_stack([np.ones(len(z)), z])
    recency = np.exp(-(times[-1] - times) / (180 * 3_600_000 * 24))

    def loss(w):
        logits = design @ w
        value = np.sum(recency * (np.logaddexp(0, logits) - y * logits))
        value += penalty * np.dot(w[1:], w[1:]) / 2
        grad = design.T @ (recency * (expit(logits) - y))
        grad[1:] += penalty * w[1:]
        return value, grad

    initial = np.zeros(design.shape[1])
    result = minimize(
        loss,
        initial,
        jac=True,
        method="L-BFGS-B",
        options={"maxiter": 500, "ftol": 1e-8, "gtol": 1e-4},
    )
    if not result.success or not np.isfinite(result.x).all():
        raise ValueError("Model optimizer did not converge; previous model retained")
    return {
        "mean": mu.tolist(),
        "scale": scale.tolist(),
        "weights": result.x.tolist(),
        "interactions": interactions,
        "penalty": penalty,
        "training_end": int(times[-1]),
    }


def split(data):
    n = len(data["y"])
    first, second = int(n * 0.6), int(n * 0.8)
    if first == 0 or second >= n:
        return (np.array([], dtype=int),) * 3
    # Label endpoints must be strictly before the next split's first feature timestamp.
    train = np.where((np.arange(n) < first) & (data["end"] < data["time"][first]))[0]
    valid = np.where(
        (np.arange(n) >= first)
        & (np.arange(n) < second)
        & (data["end"] < data["time"][second])
    )[0]
    test = np.arange(second, n)
    return train, valid, test


def evidence(z_train, z_test, y, names, horizon, direction, alpha, cfg):
    rows = []
    # Only training data chooses thresholds; no test percentile fitting.
    for col, name in enumerate(names):
        cutoff = float(np.quantile(z_train[:, col], 0.75))
        mask = z_test[:, col] > cutoff
        a, b = int(y[mask].sum()), int(mask.sum())
        c, d = int(y[~mask].sum()), int((~mask).sum())
        if b == 0 or d == 0:
            continue
        rate, control = a / b, c / d
        neff = min(effective_n(y), effective_n(mask))
        fraction = neff / len(y)
        effective_signal = min(b * fraction, effective_n(y[mask]))
        effective_control = min(d * fraction, effective_n(y[~mask]))
        se = math.sqrt(
            0.25 / max(effective_signal, 1) + 0.25 / max(effective_control, 1)
        )
        exact = float(fisher_exact([[a, b - a], [c, d - c]]).pvalue)
        dependent = float(2 * norm.sf(abs(rate - control) / max(se, 1e-12)))
        lo, hi = wilson(rate * effective_signal, effective_signal)
        rows.append(
            {
                "feature": name,
                "horizon_hours": horizon,
                "direction": direction,
                "cutoff_standardized": cutoff,
                "repeats": b,
                "control_repeats": d,
                "effective_repeats": effective_signal,
                "effective_control_repeats": effective_control,
                "successes": a,
                "rate": rate,
                "control_rate": control,
                "lift": rate - control,
                "interval_95": [lo, hi],
                "p": max(exact, dependent),
                "required_per_group": required_per_group(
                    alpha, cfg.power, cfg.detectable_lift
                ),
                "precision_required": precision_n(cfg.ci_half_width),
                "method": "Fisher + conservative autocorrelation-adjusted normal approximation",
            }
        )
    return rows


def research(bars, indices, x, cfg, round_no, previous=None):
    alpha = alpha_budget(round_no, cfg.alpha / (2 * len(cfg.pairs)))
    models, results, hypotheses = {}, [], []
    previous = previous or {}
    for horizon in cfg.horizons:
        data = dataset(bars, indices, x, horizon, cfg.min_move, cfg.fee_bps)
        train, valid, test = split(data)
        if min(len(train), len(valid), len(test)) < 20:
            results.append(
                {
                    "horizon_hours": horizon,
                    "status": "INSUFFICIENT_DATA",
                    "independent_samples": len(data["y"]),
                    "test_samples": len(test),
                }
            )
            continue
        for column, direction in enumerate(("up", "down")):
            key = f"{horizon}:{direction}"
            y = data["y"][:, column]
            if len(np.unique(y[train])) < 2:
                results.append(
                    {
                        "horizon_hours": horizon,
                        "direction": direction,
                        "status": "SINGLE_CLASS",
                    }
                )
                continue
            # Automatic interaction proposals, based on training correlation only.
            scores = [
                abs(np.corrcoef(data["x"][train, j], y[train])[0, 1])
                if np.std(data["x"][train, j]) > 1e-12
                else 0
                for j in range(len(NAMES))
            ]
            best = np.argsort(np.nan_to_num(scores))[-4:].tolist()
            interactions = [
                [a, b] for rank, a in enumerate(best) for b in best[rank + 1 :]
            ]
            candidates = []
            for penalty in (1.0, 10.0, 100.0):
                try:
                    candidates.append(
                        fit(
                            data["x"][train],
                            y[train],
                            data["time"][train],
                            interactions,
                            penalty,
                        )
                    )
                except ValueError:
                    continue
            if not candidates:
                if key in previous:
                    models[key] = previous[key]
                results.append(
                    {
                        "horizon_hours": horizon,
                        "direction": direction,
                        "status": "OPTIMIZER_FAILED",
                    }
                )
                continue
            candidate = min(
                candidates,
                key=lambda m: np.mean((predict(m, data["x"][valid]) - y[valid]) ** 2),
            )
            p = predict(candidate, data["x"][test])
            baseline = float((y[train].sum() + 0.5) / (len(train) + 1))
            old = previous.get(key)
            comparable = old and old["training_end"] < int(data["time"][test[0]])
            reference = (
                predict(old, data["x"][test])
                if comparable
                else np.full(len(test), baseline)
            )
            gain = (reference - y[test]) ** 2 - (p - y[test]) ** 2
            neff = effective_n(gain)
            standard_error = float(np.std(gain, ddof=1) / math.sqrt(neff))
            improvement = float(np.mean(gain))
            # Account for 6 competing horizon/direction promotions and repeated training rounds.
            model_alpha = alpha / (4 * len(cfg.horizons))
            lower = improvement - float(norm.ppf(1 - model_alpha)) * standard_error
            eligible = neff >= precision_n(cfg.ci_half_width) and lower > 0
            version = hashlib.sha256(
                json.dumps(candidate, sort_keys=True).encode()
            ).hexdigest()[:16]
            candidate.update(
                {
                    "version": version,
                    "baseline": baseline,
                    "horizon_hours": horizon,
                    "direction": direction,
                    "test_start": int(data["time"][test[0]]),
                    "evaluation": calibration(y[test], p),
                }
            )
            if eligible:
                models[key] = candidate
            elif old:
                models[key] = old
            # Refresh evidence for the active model, including models installed
            # before score certificates existed. A retained model does not need
            # to beat itself to become eligible for the research score.
            active = models.get(key)
            if active:
                active = dict(active)
                active["score_acceptance"] = {}
                if active["training_end"] < int(data["time"][test[0]]):
                    active_p = predict(active, data["x"][test])
                    active_gains = (baseline - y[test]) ** 2 - (active_p - y[test]) ** 2
                    active_neff = effective_n(active_gains)
                    active_se = float(
                        np.std(active_gains, ddof=1) / math.sqrt(active_neff)
                    )
                    active_lower = (
                        float(np.mean(active_gains))
                        - float(norm.ppf(1 - model_alpha)) * active_se
                    )
                    active_calibration = calibration(y[test], active_p)
                    if (
                        active_neff >= precision_n(cfg.ci_half_width)
                        and active_lower > 0
                    ):
                        active["score_acceptance"] = {
                            "design": direction_design(cfg),
                            "model_version": active["version"],
                            "gain_lower_bound": active_lower,
                            "effective_test_samples": active_neff,
                            "calibration_ece": active_calibration["ece"],
                            "evaluation_alpha": model_alpha,
                            "test_start": int(data["time"][test[0]]),
                        }
                models[key] = active
            result = {
                "horizon_hours": horizon,
                "direction": direction,
                "status": "PROMOTED"
                if eligible
                else "RETAINED"
                if old
                else "BASELINE_ONLY",
                "train_samples": len(train),
                "validation_samples": len(valid),
                "test_samples": len(test),
                "effective_test_samples": neff,
                "candidate_version": version,
                "active_version": models.get(key, {}).get("version"),
                "baseline_rate": baseline,
                "brier_gain": improvement,
                "gain_lower_bound": lower,
                "promotion_alpha": model_alpha,
                "calibration": candidate["evaluation"],
                "split": {
                    "training_end": candidate["training_end"],
                    "validation_start": int(data["time"][valid[0]]),
                    "test_start": int(data["time"][test[0]]),
                },
            }
            results.append(result)
            mu, scale = np.array(candidate["mean"]), np.array(candidate["scale"])
            z_train = expand(
                np.clip((data["x"][train] - mu) / scale, -10, 10), NAMES, interactions
            )
            z_test = expand(
                np.clip((data["x"][test] - mu) / scale, -10, 10), NAMES, interactions
            )
            names = list(NAMES) + [f"{NAMES[a]} × {NAMES[b]}" for a, b in interactions]
            hypotheses.extend(
                evidence(
                    z_train, z_test, y[test], names, horizon, direction, alpha, cfg
                )
            )
    # One multiplicity family across every tested feature, direction and horizon in this round.
    adjusted = holm([r["p"] for r in hypotheses])
    design_alpha = alpha / max(len(hypotheses), 1)
    for row, q in zip(hypotheses, adjusted):
        row["adjusted_p"] = q
        row["required_per_group"] = required_per_group(
            design_alpha, cfg.power, cfg.detectable_lift
        )
        sufficient = (
            min(row["effective_repeats"], row["effective_control_repeats"])
            >= row["required_per_group"]
        )
        precise = row["interval_95"][1] - row["interval_95"][0] <= 2 * cfg.ci_half_width
        row["status"] = (
            "SUPPORTED"
            if q <= alpha and sufficient and precise
            else "UNDERPOWERED"
            if not sufficient
            else "NOT_SUPPORTED"
        )
    hypotheses.sort(key=lambda r: (r["adjusted_p"], -abs(r["lift"])))
    return {
        "models": models,
        "evaluations": results,
        "hypotheses": hypotheses,
        "round": round_no,
        "round_alpha": alpha,
        "family_tests": len(hypotheses),
        "design": {
            "power": cfg.power,
            "minimum_detectable_lift": cfg.detectable_lift,
            "confidence": 0.95,
            "ci_half_width": cfg.ci_half_width,
            "unadjusted_required_per_group": required_per_group(
                cfg.alpha, cfg.power, cfg.detectable_lift
            ),
            "warning": "Observational associations; no causal or guaranteed profit claim.",
        },
    }
