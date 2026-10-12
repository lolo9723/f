import json
import re
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from storage import now_ms
from metrics import report


def api_server(settings, store, radar):
    class Handler(BaseHTTPRequestHandler):
        def authorized(self):
            if settings.api_token and not hmac.compare_digest(
                    self.headers.get("Authorization", ""), "Bearer " + settings.api_token):
                self.reply(401, {"error": "Sunucu erişim anahtarı gerekli."})
                return False
            return True

        def reply(self, status, value):
            body = json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            url = urlparse(self.path)
            if url.path != "/health" and not self.authorized():
                return
            if url.path == "/health":
                self.reply(200, {
                    "mode": "PAPER_ONLY", "model": settings.model_version,
                    "auth_required": bool(settings.api_token), "paper_tracking": True,
                    "last_scan_at": radar.last_scan_at,
                    "market_pairs_seen": len(radar.tickers),
                    "selected_pairs": len(radar.selected),
                    "trade_queue": radar.trade_queue.qsize(),
                    "blocked_pairs": sorted(radar.feed_blocked),
                    "error": radar.last_error,
                    "now": now_ms()
                })
            elif url.path == "/pairs":
                self.reply(200, [{"pair": pair, **meta,
                    "price": radar.tickers.get(pair, {}).get("price"),
                    "observed_at": radar.tickers.get(pair, {}).get("received_at")}
                    for pair, meta in sorted(radar.symbols.items())])
            elif url.path == "/paper-positions":
                positions = store.rows("SELECT * FROM paper_positions ORDER BY created_at DESC")
                for position in positions:
                    position["feed_blocked"] = position["pair"] in radar.feed_blocked
                self.reply(200, positions)
            elif url.path == "/opportunities":
                items = store.snapshot(
                    "SELECT * FROM opportunities WHERE observed_at>? ORDER BY rally_score DESC LIMIT 10",
                    (now_ms() - 15 * 60_000,))
                for item in items:
                    ticker = radar.tickers.get(item["pair"])
                    if ticker and now_ms() - ticker["received_at"] < 30_000:
                        item["price"] = ticker["price"]
                        item["price_observed_at"] = ticker["received_at"]
                self.reply(200, items)
            elif url.path == "/signals":
                self.reply(200, store.snapshot(
                    "SELECT * FROM signals WHERE outcome IS NULL ORDER BY created_at DESC"))
            elif url.path == "/history":
                self.reply(200, store.snapshot(
                    "SELECT * FROM signals WHERE outcome IS NOT NULL ORDER BY closed_at DESC LIMIT 500"))
            elif url.path == "/metrics":
                self.reply(200, report(store.snapshot(
                    "SELECT * FROM signals ORDER BY COALESCE(closed_at,created_at) ASC")))
            elif re.fullmatch(r"/signals/[a-f0-9-]{36}", url.path):
                sid = url.path.rsplit("/", 1)[-1]
                rows = store.snapshot("SELECT * FROM signals WHERE signal_id=?", (sid,))
                if not rows:
                    self.reply(404, {"error": "unknown signal"})
                else:
                    rows[0]["events"] = store.rows(
                        "SELECT * FROM events WHERE signal_id=? ORDER BY event_id", (sid,))
                    self.reply(200, rows[0])
            elif url.path == "/market":
                pair = parse_qs(url.query).get("pair", [""])[0]
                if pair not in radar.symbols:
                    self.reply(404, {"error": "unknown Binance TR TRY pair"})
                else:
                    self.reply(200, {
                        "pair": pair, "ticker": radar.tickers.get(pair),
                        "opportunity": (store.snapshot("SELECT * FROM opportunities WHERE pair=?",
                                                      (pair,)) or [None])[0]})
            elif url.path == "/candles":
                pair = parse_qs(url.query).get("pair", [""])[0]
                if pair not in radar.symbols:
                    self.reply(404, {"error": "unknown pair"})
                else:
                    self.reply(200, radar.charts.get(pair, {"minute": [], "hour": [], "as_of": None}))
            else:
                self.reply(404, {"error": "not found"})

        def do_POST(self):
            if not self.authorized():
                return
            if self.path not in ("/devices", "/paper-positions"):
                return self.reply(404, {"error": "not found"})
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 4096:
                    return self.reply(413, {"error": "too large"})
                body = json.loads(self.rfile.read(length))
                if self.path == "/paper-positions":
                    pair = body["pair"]
                    if pair not in radar.symbols:
                        return self.reply(400, {"error": "Binance TR TRY paritesi bulunamadı."})
                    position = store.create_paper_position(body["position_id"], pair, body["entry"],
                        body["target"], body.get("stop"), settings.maker_fee_bps + settings.exit_fee_bps,
                        now_ms())
                    return self.reply(201, position)
                token = body["token"]
                if not re.fullmatch(r"(?:Expo|Exponent)PushToken\[[^\]]{8,256}\]", token):
                    return self.reply(400, {"error": "invalid Expo push token"})
                store.register_device(token)
                self.reply(201, {"registered": True})
            except (ValueError, KeyError, TypeError) as exc:
                self.reply(400, {"error": str(exc) or "invalid JSON"})

        def log_message(self, fmt, *args):
            pass

    return ThreadingHTTPServer((settings.host, settings.port), Handler)
