import copy
import numpy as np
from radar.config import Config
from radar.features import NAMES, dataset, matrix
from radar.research import fit, predict, research, split
from radar.statistics import alpha_budget, effective_n, holm, required_per_group, wilson
from conftest import make_bars


def test_every_feature_is_causal_even_when_future_changes():
    bars = make_bars(300)
    mutated = copy.deepcopy(bars)
    for bar in mutated[220:]:
        bar["close"] *= 100
        bar["high"] *= 200
        bar["quote_volume"] *= 1000
    idx, original = matrix(bars)
    _, changed = matrix(mutated)
    np.testing.assert_array_equal(original[idx < 220], changed[idx < 220])
    assert original.shape[1] == len(NAMES) == 88
    assert not np.array_equal(original[idx >= 220], changed[idx >= 220])


def test_purged_time_split_and_nonoverlapping_windows():
    bars = make_bars()
    indices, x = matrix(bars)
    for horizon in (1, 6, 24):
        data = dataset(bars, indices, x, horizon, 0.005, 28)
        train, valid, test = split(data)
        assert max(data["end"][train]) < min(data["time"][valid])
        assert max(data["end"][valid]) < min(data["time"][test])
        assert np.all(data["time"][1:] >= data["end"][:-1])
        assert np.all(data["y"].sum(axis=1) <= 1)


def test_power_is_predeclared_and_multiplicity_increases_required_data():
    assert required_per_group() == 393
    assert required_per_group(alpha=0.0001) > required_per_group()
    assert required_per_group(delta=0.05) > 3 * required_per_group(delta=0.1)
    assert sum(alpha_budget(r) for r in range(1, 10000)) < 0.05
    np.testing.assert_allclose(holm([0.01, 0.04, 0.03]), [0.03, 0.06, 0.06])
    lo, hi = wilson(5, 10)
    assert lo < 0.25 and hi > 0.75
    assert wilson(0, 0) == [None, None]


def test_serial_dependence_reduces_effective_sample_count():
    correlated = np.repeat(np.tile([0, 1], 20), 20)
    assert effective_n(correlated) < len(correlated) / 5


def test_model_learns_predictable_synthetic_relationship():
    rng = np.random.default_rng(3)
    x = rng.normal(size=(700, len(NAMES)))
    y = (x[:, 0] + 0.1 * rng.normal(size=700) > 0).astype(int)
    model = fit(x[:500], y[:500], np.arange(500), [], 10)
    predictions = predict(model, x[500:])
    assert np.mean((predictions - y[500:]) ** 2) < 0.10


def test_noise_and_small_samples_do_not_create_supported_claims():
    bars = make_bars(700)
    indices, x = matrix(bars)
    report = research(bars, indices, x, Config(), 1)
    assert report["family_tests"] > 100
    assert all(r["status"] != "SUPPORTED" for r in report["hypotheses"])
    assert all(r["required_per_group"] > 393 for r in report["hypotheses"])
    assert all(
        r["split"]["training_end"] < r["split"]["test_start"]
        for r in report["evaluations"]
        if "split" in r
    )


def test_optimizer_failure_retains_previous_model_and_reports_failure(monkeypatch):
    import radar.research as module

    bars = make_bars(700)
    indices, x = matrix(bars)
    old = {"1:up": {"version": "prior"}}

    def fail(*args, **kwargs):
        raise ValueError("optimizer failed")

    monkeypatch.setattr(module, "fit", fail)
    report = module.research(bars, indices, x, Config(), 1, old)
    assert report["models"]["1:up"] == old["1:up"]
    assert any(r["status"] == "OPTIMIZER_FAILED" for r in report["evaluations"])
