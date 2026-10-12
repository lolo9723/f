import json
import sqlite3
import zipfile
import numpy as np
import pytest
from scipy import stats
from scipy.optimize import minimize as scipy_minimize
from radar import phone, portable_science as portable
from radar.config import Config, HOUR
from radar.pipeline import run_pair
from conftest import FakeMarket, make_bars


@pytest.mark.parametrize("df", [1, 2, 5, 20, 96, 500, 2000])
def test_portable_student_t_matches_reference_in_power_and_tail_ranges(df):
    for p in [1e-7, 0.001, 0.025, 0.5, 0.975, 0.999, 1 - 1e-7]:
        expected = stats.t.ppf(p, df)
        assert portable.student_t.ppf(p, df) == pytest.approx(
            expected, rel=2e-8, abs=1e-9
        )
    for x in [0, 0.25, 2, 5, 10]:
        assert portable.student_t.sf(x, df) == pytest.approx(
            stats.t.sf(x, df), rel=2e-10, abs=1e-12
        )


def test_portable_normal_fisher_and_logistic_optimizer_match_reference():
    rng = np.random.default_rng(83)
    for p in [1e-10, 0.001, 0.05, 0.8, 0.95, 1 - 1e-10]:
        assert portable.norm.ppf(p) == pytest.approx(stats.norm.ppf(p), abs=1e-9)
    for table in [
        np.zeros((2, 2)),
        [[0, 12], [9, 0]],
        *[rng.integers(0, 200, (2, 2)) for _ in range(60)],
    ]:
        assert portable.fisher_exact(table).pvalue == pytest.approx(
            stats.fisher_exact(table).pvalue, abs=2e-10
        )
    x = rng.normal(size=(400, 16))
    y = rng.binomial(1, portable.expit(x[:, 0] - 0.4 * x[:, 1]))

    def loss(w):
        z = x @ w
        return float(np.logaddexp(0, z).sum() - y @ z + w @ w / 2), x.T @ (
            portable.expit(z) - y
        ) + w

    options = {"maxiter": 500, "ftol": 1e-8, "gtol": 1e-4}
    actual = portable.minimize(loss, np.zeros(16), options=options)
    expected = scipy_minimize(
        loss, np.zeros(16), jac=True, method="L-BFGS-B", options=options
    )
    assert actual.success
    assert actual.fun == pytest.approx(expected.fun, abs=1e-5)
    assert (
        np.max(np.abs(portable.expit(x @ actual.x) - portable.expit(x @ expected.x)))
        < 1e-4
    )
    assert not portable.minimize(loss, np.zeros(16), options={"maxiter": 0}).success


def test_phone_request_preserves_api_errors_queues_and_paper_idempotency(tmp_path):
    pairs = json.dumps(["BTCTRY"])

    def call(method, path, body="{}", query=""):
        return json.loads(
            phone.request(str(tmp_path), pairs, method, path, query, body)
        )

    assert call("GET", "/health")["body"]["runtime"] == "PHONE"
    assert call("GET", "/v2/snapshot")["body"]["audit"]["ok"] is False
    assert call("POST", "/v2/control/reconcile")["queued"] == ["BTCTRY"]
    payload = json.dumps(
        {
            "position_id": "fd25e3c0-1c0b-40b6-9d75-4c5a30427900",
            "pair": "BTCTRY",
            "entry": 100,
            "target": 103,
            "stop": 98,
        }
    )
    first = call("POST", "/paper-positions", payload)
    assert first["status"] == 201
    assert first["queued"] == ["BTCTRY"]
    assert call("POST", "/paper-positions", payload)["body"] == first["body"]
    assert call("GET", "/v2/stop-loss/research", query="pair=ETHBTC")["status"] == 404
    assert call("POST", "/paper-positions", "{")["status"] == 400
    assert call("GET", "/paper-positions")["body"][0]["stop"] == 98


def test_phone_charging_defers_training_but_preserves_causal_features_and_research_history(
    tmp_path,
):
    bars = make_bars(720)
    now = bars[-1]["time"] + HOUR + 1000
    cfg = Config(pairs=("BTCTRY",), lookback_days=30)
    store = phone.PhoneStore(tmp_path / "data")
    market = FakeMarket(bars)
    first = run_pair(
        store,
        market,
        cfg,
        "BTCTRY",
        "battery",
        now=now,
        clock=lambda: now,
        allow_training=False,
    )
    assert first["stages"]["research"] == "WAITING_CHARGE"
    assert first["stages"]["stop_research"] == "WAITING_CHARGE"
    state = store.read("BTCTRY")
    assert state.get("trained_at") is None and state.get("stop_trained_at") is None
    assert state["latest_features"] and state["stop_context"]["atr_fraction"] > 0
    assert state["stop_suggestions"] == []
    run_pair(store, market, cfg, "BTCTRY", "charging", now=now, clock=lambda: now)
    assert store.read("BTCTRY")["research"]["round"] == 1
    assert store.read("BTCTRY")["stop_research"]["round"] == 1
    for i in range(3):
        run_pair(
            store,
            market,
            cfg,
            "BTCTRY",
            f"poll-{i}",
            now=now,
            clock=lambda: now,
            allow_training=False,
        )
    with sqlite3.connect(store.root / "research.sqlite3") as db:
        features = db.execute(
            "SELECT payload FROM records WHERE kind='features'"
        ).fetchall()
        assert len(features) == 1
        assert json.loads(features[0][0])["features"] == state["latest_features"]
        assert json.loads(features[0][0])["observed_at"] == bars[-1]["time"]
        assert (
            db.execute("SELECT COUNT(*) FROM records WHERE kind='candle'").fetchone()[0]
            == 720
        )
        assert (
            db.execute("SELECT COUNT(*) FROM records WHERE kind='research'").fetchone()[
                0
            ]
            == 1
        )
        assert (
            db.execute(
                "SELECT COUNT(*) FROM records WHERE kind='stop_research'"
            ).fetchone()[0]
            == 1
        )
    assert len(list(store.root.glob("BTCTRY-*.json.gz"))) <= 4
    archive = phone.backup(str(store.root), json.dumps(["BTCTRY"]))
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        assert "research.sqlite3" in z.namelist()
        manifest = json.loads(z.read("manifest.json"))
        assert manifest["contains_credentials"] is False
        meta = json.loads(z.read("state/BTCTRY.json"))
        assert "state/" + meta["key"] in z.namelist()
        assert json.loads(z.read("manifest.json"))["runtime"] == "PHONE"


def test_phone_cycle_failures_are_recorded_without_losing_last_good_data(
    tmp_path, monkeypatch
):
    class Broken:
        source = "Binance TR"

        def candles(self, *args):
            raise OSError("offline")

    monkeypatch.setattr(phone, "PhoneMarket", Broken)
    result = json.loads(phone.cycle(str(tmp_path), json.dumps(["BTCTRY"]), False))
    assert result["results"][0]["status"] == "FAILED"
    assert phone.PhoneStore(tmp_path).metadata("BTCTRY")["error"] == "offline"
    assert phone.PhoneStore(tmp_path).read("BTCTRY") is None
    with pytest.raises(ValueError):
        phone.config(json.dumps(["BTCTRY", "BTCTRY"]))
