import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from config import Settings
from http_api import api_server
from metrics import report
from service import Radar
from storage import Store

HOUR = 3600_000
T = 1_800_000_000_000


class PaperTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(str(Path(self.tmp.name) / 'paper.db'))

    def tearDown(self):
        self.tmp.cleanup()

    def new(self, stop=None):
        return self.store.create_paper_position(str(uuid.uuid4()), 'SOLTRY', 100, 103, stop, 20, T)

    def get(self, position):
        return self.store.rows('SELECT * FROM paper_positions WHERE position_id=?', (position['position_id'],))[0]

    def test_exact_hit_time_multiple_positions_persistence_and_metrics(self):
        first, second = self.new(), self.new(98)
        self.store.trade('SOLTRY', 1, T - 1, 104)
        self.assertEqual(self.get(first)['state'], 'TRACKING')
        self.store.trade('SOLTRY', 2, T + 17 * HOUR + 12 * 60000, 103.1)
        for position in (first, second):
            self.assertEqual(self.get(position)['target_hit_at'], T + 17 * HOUR + 12 * 60000)
            self.assertAlmostEqual(self.get(position)['net_return_pct'], 2.8)
        reopened = Store(self.store.path)
        self.assertEqual(reopened.rows('SELECT state FROM paper_positions')[0]['state'], 'SUCCESS')
        self.assertEqual(report(self.store.rows('SELECT * FROM signals'))['independent_forward_trades'], 0)

    def test_stop_precedes_later_target_and_duplicate_does_not_rewrite(self):
        position = self.new(98)
        self.store.trade('SOLTRY', 1, T + 1000, 97)
        self.store.trade('SOLTRY', 2, T + 2000, 105)
        self.store.trade('SOLTRY', 1, T + 1000, 103)
        result = self.get(position)
        self.assertEqual(result['state'], 'STOPPED')
        self.assertIsNone(result['target_hit_at'])
        self.assertAlmostEqual(result['net_return_pct'], -3.2)

    def test_target_at_48h_is_expired_no_fabricated_price(self):
        position = self.new()
        self.store.expire(T + 49 * HOUR, lambda *_: True)
        self.assertEqual(self.get(position)['state'], 'TRACKING')
        self.store.trade('SOLTRY', 1, T + 48 * HOUR, 104)
        self.assertEqual(self.get(position)['state'], 'TIME_EXPIRED')
        self.assertIsNone(self.get(position)['target_hit_at'])

    def test_idempotent_create_and_invalid_levels(self):
        position = self.new()
        again = self.store.create_paper_position(position['position_id'], 'SOLTRY', 100, 103, None, 20, T + 1000)
        self.assertEqual(again['created_at'], T)
        for entry, target, stop in ((0, 103, None), (100, 99, None), (100, 103, 101), (float('nan'), 103, None)):
            with self.assertRaises(ValueError):
                self.store.create_paper_position(str(uuid.uuid4()), 'SOLTRY', entry, target, stop, 20, T)

    def test_http_auth_and_manual_pair_is_subscribed(self):
        settings = Settings(db_path=self.store.path, host='127.0.0.1', port=0, api_token='test-secret')
        radar = Radar(settings, self.store)
        radar.symbols = {'SOLTRY': {'coin': 'SOL', 'display': 'SOL/TRY', 'tick': '0.01'}}
        server = api_server(settings, self.store, radar)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        base = f'http://127.0.0.1:{server.server_port}'
        try:
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(base + '/paper-positions')
            self.assertEqual(error.exception.code, 401)
            body = json.dumps({'position_id': str(uuid.uuid4()), 'pair': 'SOLTRY', 'entry': 100, 'target': 103}).encode()
            request = urllib.request.Request(base + '/paper-positions', data=body,
                headers={'Authorization': 'Bearer test-secret', 'Content-Type': 'application/json'})
            with urllib.request.urlopen(request) as response:
                self.assertEqual(json.load(response)['state'], 'TRACKING')
            self.assertIn('SOLTRY', radar.selected_pairs())
        finally:
            server.shutdown(); thread.join(); server.server_close()


if __name__ == '__main__':
    unittest.main()
