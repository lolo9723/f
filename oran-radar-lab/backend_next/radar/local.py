"""Runnable local equivalent for development; cloud scheduling is in infra/template.yaml."""

import argparse
import json
import queue
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from .api import API
from .config import Config
from .market import Market
from .pipeline import run_pair
from .storage import LocalStore, clock_ms


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="local-data")
    parser.add_argument("--port", type=int, default=8788)
    parser.add_argument("--once", action="store_true")
    parser.add_argument(
        "--demo",
        action="store_true",
        help="Explicitly synthetic offline data; use a separate --root",
    )
    args = parser.parse_args()
    cfg, store = Config(), LocalStore(args.root)
    from .demo import DemoMarket

    market = DemoMarket(cfg.lookback_days) if args.demo else Market()
    if args.once:
        for pair in cfg.pairs:
            print(json.dumps(run_pair(store, market, cfg, pair, f"local:{clock_ms()}")))
        store.heartbeat(clock_ms())
        return
    tasks = queue.Queue()
    pending = set()
    lock = threading.Lock()

    def enqueue(pair, job, force=False):
        with lock:
            if pair not in pending:
                pending.add(pair)
                tasks.put((pair, job, force))

    def scheduler():
        while True:
            for pair in cfg.pairs:
                enqueue(pair, f"local:{clock_ms()}")
            store.heartbeat(clock_ms())
            threading.Event().wait(300)

    def worker():
        while True:
            pair, job, force = tasks.get()
            try:
                print(
                    json.dumps(
                        run_pair(store, market, cfg, pair, job, force_train=force)
                    ),
                    flush=True,
                )
            except Exception as exc:
                print(f"{pair}: {exc}", flush=True)
            finally:
                with lock:
                    pending.discard(pair)
                tasks.task_done()

    api = API(store, cfg, enqueue)

    class Handler(BaseHTTPRequestHandler):
        def dispatch(self):
            parsed = urlsplit(self.path)
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length > 4096:
                    status, result = 413, {"error": "Request too large"}
                else:
                    status, result = api.handle(
                        self.command,
                        parsed.path,
                        parsed.query,
                        dict(self.headers.items()),
                        self.rfile.read(length).decode(),
                        clock_ms(),
                    )
            except (ValueError, KeyError, TypeError) as exc:
                status, result = 400, {"error": str(exc)}
            except Exception:
                status, result = 503, {"error": "Backend temporarily unavailable"}
            data = json.dumps(result, ensure_ascii=False, allow_nan=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        do_GET = dispatch
        do_POST = dispatch

        def log_message(self, *args):
            pass

    threading.Thread(target=worker, daemon=True).start()
    threading.Thread(target=scheduler, daemon=True).start()
    print(
        f"Lab API http://127.0.0.1:{args.port}; configured pairs={cfg.pairs}",
        flush=True,
    )
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
