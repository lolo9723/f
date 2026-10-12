import json
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path

from config import Settings
from http_api import api_server
from service import Radar
from storage import Store


class ApiTests(unittest.TestCase):
    def test_health_metrics_and_empty_lists(self):
        with tempfile.TemporaryDirectory() as folder:
            settings = Settings(db_path=str(Path(folder) / "api.sqlite3"), host="127.0.0.1", port=0)
            store = Store(settings.db_path)
            radar = Radar(settings, store)
            server = api_server(settings, store, radar)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                for endpoint in ("/health", "/opportunities", "/signals", "/history", "/metrics"):
                    with urllib.request.urlopen(f"http://127.0.0.1:{server.server_port}{endpoint}",
                                                timeout=2) as response:
                        payload = json.load(response)
                        self.assertIsNotNone(payload)
                self.assertEqual(payload["model_status"], "PROVISIONAL_UNCALIBRATED")
            finally:
                server.shutdown()
                thread.join()
                server.server_close()


if __name__ == "__main__":
    unittest.main()
