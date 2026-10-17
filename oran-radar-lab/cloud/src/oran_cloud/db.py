import hashlib
import json
import sqlite3
import time
from pathlib import Path


def now_ms():
    return time.time_ns() // 1_000_000


def identity(*parts):
    return hashlib.sha256(json.dumps(parts, separators=(',', ':')).encode()).hexdigest()


def connect(path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(path, timeout=30)
    db.row_factory = sqlite3.Row
    db.execute('PRAGMA journal_mode=WAL')
    db.executescript(Path(__file__).with_name('schema.sql').read_text())
    db.commit()
    return db


def issue(db, task_id, kind, detail, start=None, end=None):
    db.execute('''INSERT INTO data_quality_issues VALUES(?,?,?,?,?,?,?,NULL)
      ON CONFLICT(issue_id) DO UPDATE SET detail=excluded.detail,resolved_at=NULL''',
      (identity(task_id, kind, start, end), task_id, kind, start, end, str(detail)[:2000], now_ms()))


def inventory(db, exchange, records, source):
    observed = now_ms()
    with db:
        for s in records:
            symbol_id = f'{exchange}:spot:{s["symbol"]}'
            old = db.execute('SELECT status FROM symbols WHERE symbol_id=?', (symbol_id,)).fetchone()
            for ticker in [s.get('baseAsset'), s.get('quoteAsset')]:
                if ticker:
                    db.execute('INSERT OR IGNORE INTO assets VALUES(?,?,?,?)',
                               (f'{exchange}:{ticker}', exchange, ticker, 'exchange_ticker_only'))
            db.execute('INSERT OR IGNORE INTO markets VALUES(?,?,?)', (f'{exchange}:spot', exchange, 'spot'))
            status = s.get('status', 'unknown')
            db.execute('''INSERT INTO symbols VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
              ON CONFLICT(symbol_id) DO UPDATE SET
              base_asset=COALESCE(excluded.base_asset,symbols.base_asset),
              quote_asset=COALESCE(excluded.quote_asset,symbols.quote_asset),
              status=CASE WHEN excluded.status='archive_only' THEN symbols.status ELSE excluded.status END,
              symbol_type=COALESCE(excluded.symbol_type,symbols.symbol_type),
              rules_json=CASE WHEN excluded.status='archive_only' THEN symbols.rules_json ELSE excluded.rules_json END,
              source=excluded.source,last_seen=excluded.last_seen''',
              (symbol_id, exchange, s['symbol'], s.get('baseAsset'), s.get('quoteAsset'), status,
               s.get('type'), json.dumps(s, sort_keys=True), source, observed, observed, 'unverified'))
            if old is None or (status != 'archive_only' and old['status'] != status):
                # Observation time is not an invented listing/delisting effective date.
                db.execute('INSERT OR IGNORE INTO listing_events VALUES(?,?,?,?,?,NULL)',
                           (identity(symbol_id, observed, status), symbol_id, observed, status, source))
        db.execute('INSERT INTO inventory_runs VALUES(?,?,?,?,?,?,?)',
                   (identity(exchange, source, observed), exchange, source, observed, 'complete', len(records), None))
