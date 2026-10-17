"""Single-writer, bounded ingestion. Objects first, atomic manifest/checkpoint last."""
import datetime as dt
import hashlib
import json
import time
from .candles import INTERVAL_MS, parse_rows, zip_rows, parquet_bytes, read_parquet
from .db import identity, issue, now_ms
from .sources import ARCHIVE, rest_klines
from .store import QuotaExceeded

UTC = dt.timezone.utc


def period_bounds(period):
    start = dt.datetime.fromisoformat(period + '-01' if len(period) == 7 else period).replace(tzinfo=UTC)
    if len(period) == 7:
        end = (start.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
    else:
        end = start + dt.timedelta(days=1)
    return int(start.timestamp() * 1000), int(end.timestamp() * 1000)


def months(start, end):
    cursor = dt.date.fromisoformat(start + '-01')
    stop = dt.date.fromisoformat(end + '-01')
    while cursor <= stop:
        yield cursor.strftime('%Y-%m')
        cursor = (cursor.replace(day=28) + dt.timedelta(days=4)).replace(day=1)


def plan(db, symbols, intervals, start, end):
    inserted = 0
    for interval in intervals:
        if interval not in INTERVAL_MS:
            raise ValueError('unsupported fixed interval')
    with db:
        for s in symbols:
            for interval in intervals:
                for period in months(start, end):
                    begin, finish = period_bounds(period)
                    # Monthly jobs only target closed months. Current days are separate jobs.
                    if finish > now_ms():
                        continue
                    exchange, symbol = s['exchange'], s['symbol']
                    priority = ({'1d': 10, '1h': 20, '15m': 40, '5m': 50, '1m': 60}[interval]
                                - (5 if exchange == 'binance_tr' else 0))
                    task_id = identity(exchange, symbol, interval, period)
                    inserted += db.execute('''INSERT OR IGNORE INTO ingestion_tasks
                      VALUES(?,?,?,?,?,?,?,?,?,'pending',0,0,NULL,?)''',
                      (task_id, exchange, symbol, interval, period, begin, finish,
                       'archive' if exchange == 'binance_global' else 'rest', priority, now_ms())).rowcount
    return inserted


def plan_recent(db, symbols, intervals, days=3, at=None):
    today = dt.datetime.fromtimestamp((at or now_ms()) / 1000, UTC).date()
    count = 0
    with db:
        for s in symbols:
            for interval in intervals:
                for ago in range(days, 0, -1):
                    period = (today - dt.timedelta(days=ago)).isoformat()
                    start, end = period_bounds(period)
                    # Avoid overlapping authoritative monthly data/tasks.
                    monthly = db.execute('''SELECT 1 FROM ingestion_tasks WHERE exchange=? AND symbol=?
                      AND interval=? AND period=?''', (s['exchange'], s['symbol'], interval, period[:7])).fetchone()
                    if monthly:
                        continue
                    count += db.execute('''INSERT OR IGNORE INTO ingestion_tasks
                      VALUES(?,?,?,?,?,?,?,?,?,'pending',0,0,NULL,?)''',
                      (identity(s['exchange'], s['symbol'], interval, period), s['exchange'], s['symbol'],
                       interval, period, start, end, 'archive' if s['exchange']=='binance_global' else 'rest', 5, now_ms())).rowcount
    return count


def archive_url(task):
    kind = 'monthly' if len(task['period']) == 7 else 'daily'
    s, i, p = task['symbol'], task['interval'], task['period']
    return f'{ARCHIVE}/data/spot/{kind}/klines/{s}/{i}/{s}-{i}-{p}.zip'


def fetch_task(db, http, task):
    if task['source'] == 'archive':
        url = archive_url(task)
        blob = http.get(url).content
        checksum = http.get(url + '.CHECKSUM').text.split()[0].lower()
        digest = hashlib.sha256(blob).hexdigest()
        if checksum != digest:
            raise ValueError('official SHA256 checksum mismatch')
        return list(zip_rows(blob)), url, digest
    row = db.execute('SELECT symbol_type FROM symbols WHERE exchange=? AND symbol=?',
                     (task['exchange'], task['symbol'])).fetchone()
    pages, sources = [], set()
    for rows, url in rest_klines(http, task['exchange'], task['symbol'], task['interval'],
                                 task['start_ms'], task['end_ms'], row['symbol_type'] if row else None):
        pages.extend(rows); sources.add(url)
    blob = json.dumps(pages, separators=(',', ':')).encode()
    return pages, ','.join(sorted(sources)), hashlib.sha256(blob).hexdigest()


def ingest_task(db, http, store, task, fetcher=fetch_task):
    old = db.execute('SELECT * FROM raw_data_manifest WHERE task_id=?', (task['task_id'],)).fetchone()
    if old and old['status'] == 'complete':
        # Detect local/object corruption before claiming the cached work is successful.
        try:
            valid=hashlib.sha256(store.get(old['object_key'])).hexdigest() == old['parquet_sha256']
        except FileNotFoundError:
            valid=False
        if valid:return 'cached'
        with db:
            db.execute("UPDATE raw_data_manifest SET status='invalid' WHERE task_id=?",(task['task_id'],))
            issue(db,task['task_id'],'stored_corruption','Manifest object missing or corrupted')
        raise ValueError('stored parquet hash mismatch')
    rows, url, digest = fetcher(db, http, task)
    good, bad, gaps, unit = parse_rows(rows, task['interval'], task['start_ms'], task['end_ms'])
    if bad:
        blob = json.dumps({'source': url, 'bad_rows': bad}, ensure_ascii=False).encode()
        store.put(f'quarantine/{task["task_id"]}/{digest}.json', blob)
        with db:
            issue(db, task['task_id'], 'invalid_rows', f'{len(bad)} invalid rows; full partition rejected')
        raise ValueError(f'{len(bad)} invalid rows quarantined; no checkpoint advanced')
    if not good:
        raise ValueError('empty period; not proof of pre-listing/delisting')
    blob = parquet_bytes(good)
    parquet_hash=hashlib.sha256(blob).hexdigest()
    key = f'{task["exchange"]}/spot/{task["symbol"]}/{task["interval"]}/{task["period"][:4]}/{task["period"][5:7]}/{task["period"]}-{digest[:20]}-{parquet_hash[:12]}.parquet'
    store.put(key, blob)
    status = 'partial' if gaps else 'complete'
    with db:
        db.execute('UPDATE data_quality_issues SET resolved_at=? WHERE task_id=? AND resolved_at IS NULL',
                   (now_ms(), task['task_id']))
        for start, end in gaps:
            issue(db, task['task_id'], 'missing_range', 'No candle; trading status for this range unverified', start, end)
        db.execute('''INSERT INTO raw_data_manifest VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
          ON CONFLICT(task_id) DO UPDATE SET object_key=excluded.object_key,
          source_sha256=excluded.source_sha256,parquet_sha256=excluded.parquet_sha256,
          source_url=excluded.source_url,row_count=excluded.row_count,bytes=excluded.bytes,
          first_ms=excluded.first_ms,last_ms=excluded.last_ms,status=excluded.status,
          validated_at=excluded.validated_at,timestamp_unit=excluded.timestamp_unit''',
          (task['task_id'], key, url, digest, parquet_hash, len(good), len(blob),
           good[0].open_ms, good[-1].close_ms, status, now_ms(), unit, 'ohlcv-v1'))
        # The task queue is authoritative. A high-water mark must never skip unresolved earlier tasks.
        if status == 'complete':
            db.execute('''INSERT INTO ingestion_checkpoints VALUES(?,?,?,?,?)
              ON CONFLICT(exchange,symbol,interval) DO UPDATE SET
              last_verified_ms=MAX(last_verified_ms,excluded.last_verified_ms),updated_at=excluded.updated_at''',
              (task['exchange'], task['symbol'], task['interval'], good[-1].close_ms, now_ms()))
        db.execute('UPDATE ingestion_tasks SET status=?,error=NULL,retry_after=?,updated_at=? WHERE task_id=?',
                   (status, now_ms()+86_400_000 if gaps else 0, now_ms(), task['task_id']))
        if status=='complete' and len(task['period'])==7:
            # Retain daily objects, but exclude superseded records from logical row counts.
            daily=db.execute('''SELECT task_id FROM ingestion_tasks WHERE exchange=? AND symbol=?
              AND interval=? AND length(period)=10 AND substr(period,1,7)=?''',
              (task['exchange'],task['symbol'],task['interval'],task['period'])).fetchall()
            for r in daily:
                db.execute("UPDATE raw_data_manifest SET status='superseded' WHERE task_id=?",(r['task_id'],))
                db.execute("UPDATE ingestion_tasks SET status='superseded' WHERE task_id=?",(r['task_id'],))
    return status


def run(db, http, store, max_files=12, max_seconds=240):
    started = time.monotonic(); result = {'complete': 0, 'partial': 0, 'failed': 0, 'paused': False}
    # One process only (CLI holds a file lock); a killed run leaves a safely retryable task.
    with db:
        db.execute("UPDATE ingestion_tasks SET status='pending' WHERE status='running'")
    for _ in range(max_files):
        if time.monotonic() - started >= max_seconds:
            break
        task = db.execute('''SELECT * FROM ingestion_tasks WHERE status IN ('pending','failed','partial')
          AND retry_after<=? ORDER BY priority,attempts,start_ms,symbol LIMIT 1''', (now_ms(),)).fetchone()
        if not task:
            break
        with db:
            db.execute("UPDATE ingestion_tasks SET status='running',attempts=attempts+1,updated_at=? WHERE task_id=?",
                       (now_ms(), task['task_id']))
        try:
            status = ingest_task(db, http, store, task)
            if status != 'cached':
                result[status] += 1
            else:
                with db:
                    db.execute("UPDATE ingestion_tasks SET status='complete' WHERE task_id=?", (task['task_id'],))
        except QuotaExceeded as e:
            with db:
                db.execute("UPDATE ingestion_tasks SET status='pending',error=?,updated_at=? WHERE task_id=?",
                           (str(e), now_ms(), task['task_id']))
            result['paused'] = True
            break
        except Exception as e:
            with db:
                issue(db, task['task_id'], 'ingestion_error', str(e))
                db.execute("UPDATE ingestion_tasks SET status='failed',error=?,retry_after=?,updated_at=? WHERE task_id=?",
                           (str(e)[:2000], now_ms()+min(86_400_000, 60_000*2**min(task['attempts'],10)), now_ms(), task['task_id']))
            result['failed'] += 1
        print(json.dumps({'task': task['task_id'], 'symbol': task['symbol'], 'period': task['period'], **result}), flush=True)
    return result


def load_candles(db, store, exchange, symbol, interval):
    candles = {}
    manifests = db.execute('''SELECT m.* FROM raw_data_manifest m JOIN ingestion_tasks t USING(task_id)
      WHERE t.exchange=? AND t.symbol=? AND t.interval=? AND m.status IN ('complete','partial') ORDER BY m.first_ms''', (exchange, symbol, interval)).fetchall()
    for m in manifests:
        blob = store.get(m['object_key'])
        if hashlib.sha256(blob).hexdigest() != m['parquet_sha256']:
            raise ValueError('corrupt dataset')
        for candle in read_parquet(blob):
            if candle.open_ms in candles and candles[candle.open_ms] != candle:
                raise ValueError('conflicting overlapping partitions')
            candles[candle.open_ms] = candle
    return sorted(candles.values(), key=lambda c: c.open_ms), identity(*[m['parquet_sha256'] for m in manifests])
