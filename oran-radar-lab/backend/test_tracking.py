import tempfile
import unittest
from pathlib import Path

from storage import Store
from metrics import report


SCENARIO = {
    "entry": 99.0, "target": 102.0, "stop": 97.0,
    "entry_probabilities": {"6": .62},
    "target_before_stop": .63, "expected_net_pct": .41,
}
META = {"coin": "XYZ", "display": "XYZ/TRY"}
HOUR = 3600_000


class TrackingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(str(Path(self.tmp.name) / "test.db"))

    def tearDown(self):
        self.tmp.cleanup()

    def new(self, at=1_800_000_000_000, pair="XYZTRY"):
        return self.store.create_signal(pair, META, 100.0, 86, SCENARIO,
                                        {"source": "Binance TR"}, "NEUTRAL", "test-v1",
                                        1000, 20, 8, at)

    def get(self, sid):
        return self.store.rows("SELECT * FROM signals WHERE signal_id=?", (sid,))[0]

    def test_entry_and_target_trade_sequence(self):
        t = 1_800_000_000_000
        sid = self.new(t)
        self.store.trade("XYZTRY", 1, t + 1000, 99.0)
        self.assertEqual(self.get(sid)["state"], "TARGET ACTIVE")
        self.store.trade("XYZTRY", 1, t + 1000, 99.0)  # duplicate replay
        self.store.trade("XYZTRY", 2, t + 2000, 102.0)
        self.assertEqual(self.get(sid)["outcome"], "SUCCESS")
        self.assertEqual(len(self.store.rows("SELECT * FROM events WHERE signal_id=?", (sid,))), 4)
        self.assertGreater(self.get(sid)["net_return_pct"], 0)

    def test_stop_and_time_expiry(self):
        t = 1_800_000_000_000
        sid = self.new(t)
        self.store.trade("XYZTRY", 1, t + 1000, 98.5)
        self.store.trade("XYZTRY", 2, t + 2000, 96.0)
        self.assertEqual(self.get(sid)["outcome"], "STOPPED")
        sid2 = self.new(t + 13 * HOUR)
        self.store.trade("XYZTRY", 3, t + 13 * HOUR + 1000, 99)
        self.store.trade("XYZTRY", 4, t + 13 * HOUR + 49 * HOUR, 100)
        self.assertEqual(self.get(sid2)["outcome"], "TIME_EXPIRED")
        self.assertEqual(self.get(sid2)["closed_at"], t + 62 * HOUR)

    def test_unfilled_is_not_financial_loss_and_stale_feed_is_held(self):
        t = 1_800_000_000_000
        sid = self.new(t)
        self.store.expire(t + 9 * HOUR, lambda pair, deadline: False)
        self.assertIsNone(self.get(sid)["outcome"])
        self.store.expire(t + 9 * HOUR, lambda pair, deadline: True)
        self.assertEqual(self.get(sid)["outcome"], "ENTRY_NOT_FILLED")
        self.assertIsNone(self.get(sid)["net_return_pct"])
        self.assertEqual(report(self.store.rows("SELECT * FROM signals"))["entry_not_filled"], 1)

    def test_cancel_and_no_duplicate_open_pair(self):
        t = 1_800_000_000_000
        sid = self.new(t)
        self.assertIsNone(self.new(t + 1))
        self.store.cancel_entry("XYZTRY", t + 10, "regime change")
        self.assertEqual(self.get(sid)["outcome"], "CANCELLED")
        self.assertIsNotNone(self.new(t + 13 * HOUR))


if __name__ == "__main__":
    unittest.main()
