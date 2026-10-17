import csv
import hashlib
import io
import json
import sqlite3
import zipfile
from dataclasses import asdict
import pytest
from oran_cloud.db import connect, inventory
from oran_cloud.candles import parse_rows, timestamp_ms, parquet_bytes, read_parquet
from oran_cloud.ingestion import plan, period_bounds, ingest_task, run, load_candles
from oran_cloud.store import LocalStore, QuotaExceeded
from oran_cloud.quota import reserve
from oran_cloud.sources import tr_kline_url


@pytest.fixture
def state(tmp_path):
    db=connect(tmp_path/'state.sqlite'); store=LocalStore(tmp_path/'objects')
    inventory(db,'binance_global',[{'symbol':'BTCUSDT','baseAsset':'BTC','quoteAsset':'USDT','status':'TRADING'}],'official-test-fixture')
    yield db,store
    db.close()


def rows(period='2025-01-01',unit='ms',interval='1d',count=1):
    start,end=period_bounds(period); step={'1d':86400_000,'1h':3600_000}[interval]
    factor=1000 if unit=='us' else 1
    return [[(start+i*step)*factor,'100','110','90','105','10',
             (start+(i+1)*step)*factor-1,'1000','15','5','500','0'] for i in range(count)]


def task(db,period='2025-01-01',interval='1d'):
    start,end=period_bounds(period)
    db.execute("INSERT OR IGNORE INTO ingestion_tasks VALUES('t','binance_global','BTCUSDT',?,?,?,?,'archive',1,'pending',0,0,NULL,0)",(interval,period,start,end)); db.commit()
    return db.execute("SELECT * FROM ingestion_tasks WHERE task_id='t'").fetchone()


def fetch_with(data):
    return lambda *_:(data,'https://data.binance.vision/test-fixture',hashlib.sha256(json.dumps(data).encode()).hexdigest())


def test_ms_and_microseconds_are_identical():
    start,end=period_bounds('2025-01-01')
    ms=parse_rows(rows(),'1d',start,end); us=parse_rows(rows(unit='us'),'1d',start,end)
    assert ms[0]==us[0] and ms[1:3]==([],[]) and us[3]=='us'


@pytest.mark.parametrize('value',[1_700_000_000,1,-1,0])
def test_bad_timestamp_units_rejected(value):
    with pytest.raises(ValueError): timestamp_ms(value)


@pytest.mark.parametrize('index,value',[(1,'NaN'),(2,'50'),(5,'-1'),(8,'1.5'),(9,'11')])
def test_bad_values_quarantined(index,value):
    data=rows();data[0][index]=value;start,end=period_bounds('2025-01-01')
    good,bad,gaps,_=parse_rows(data,'1d',start,end)
    assert not good and bad and gaps


def test_empty_row_rejected():
    start,end=period_bounds('2025-01-01')
    assert parse_rows([[]],'1d',start,end)[1]


def test_dedup_and_conflict():
    data=rows();start,end=period_bounds('2025-01-01')
    assert len(parse_rows(data*2,'1d',start,end)[0])==1
    other=data[0].copy();other[4]='104'
    assert 'conflicting' in parse_rows(data+[other],'1d',start,end)[1][0]['reason']


def test_nonmonotonic_rejected():
    start,end=period_bounds('2025-01')
    assert parse_rows(rows(count=2)[::-1],'1d',start,end)[1]


def test_parquet_exact_numeric_strings():
    start,end=period_bounds('2025-01-01'); data=rows();data[0][1]='100.000000000000000001'
    good,*_=parse_rows(data,'1d',start,end)
    assert read_parquet(parquet_bytes(good))==good


def test_idempotent_resume_never_downloads_verified_data(state):
    db,store=state;t=task(db)
    assert ingest_task(db,None,store,t,fetch_with(rows()))=='complete'
    def forbidden(*_): raise AssertionError('unexpected repeat download')
    assert ingest_task(db,None,store,t,forbidden)=='cached'
    assert db.execute('SELECT COUNT(*) FROM raw_data_manifest').fetchone()[0]==1
    assert len(load_candles(db,store,'binance_global','BTCUSDT','1d')[0])==1


def test_partial_never_advances_checkpoint(state):
    db,store=state;t=task(db,'2025-01')
    assert ingest_task(db,None,store,t,fetch_with(rows()))=='partial'
    assert db.execute('SELECT COUNT(*) FROM ingestion_checkpoints').fetchone()[0]==0
    assert db.execute("SELECT status FROM ingestion_tasks WHERE task_id='t'").fetchone()[0]=='partial'


def test_corruption_rejects_entire_partition(state):
    db,store=state;t=task(db); data=rows();data[0][2]='1'
    with pytest.raises(ValueError):ingest_task(db,None,store,t,fetch_with(data))
    assert not db.execute('SELECT * FROM raw_data_manifest').fetchone()
    assert list(store.root.rglob('*.json'))


def test_crash_after_object_write_can_resume(state):
    db,store=state;t=task(db)
    original=store.put
    def crash(key,blob):original(key,blob);raise RuntimeError('simulated power loss')
    store.put=crash
    with pytest.raises(RuntimeError):ingest_task(db,None,store,t,fetch_with(rows()))
    assert not db.execute('SELECT * FROM ingestion_checkpoints').fetchone()
    store.put=original
    assert ingest_task(db,None,store,t,fetch_with(rows()))=='complete'
    assert len(list(store.root.rglob('*.parquet')))==1


def test_stored_corruption_detected(state):
    db,store=state;t=task(db);ingest_task(db,None,store,t,fetch_with(rows()))
    next(store.root.rglob('*.parquet')).write_bytes(b'corrupt')
    with pytest.raises(ValueError,match='hash mismatch'):ingest_task(db,None,store,t,fetch_with(rows()))
    from oran_cloud.report import coverage
    assert coverage(db,store)['totals']['rows']==0


def test_market_identity_separate(state):
    db,_=state;inventory(db,'binance_tr',[{'symbol':'BTC_TRY','baseAsset':'BTC','quoteAsset':'TRY','type':1,'status':'enabled'}],'TR-fixture')
    assert db.execute('SELECT COUNT(*) FROM symbols').fetchone()[0]==2
    assert db.execute("SELECT COUNT(*) FROM assets WHERE ticker='BTC'").fetchone()[0]==2


def test_planning_repeat_safe_and_unknown_history(state):
    db,_=state; symbols=[dict(x) for x in db.execute('SELECT * FROM symbols')]
    assert plan(db,symbols,['1d','1h'],'2024-12','2025-01')==4
    assert plan(db,symbols,['1d','1h'],'2024-12','2025-01')==0
    assert db.execute('SELECT history_status FROM symbols').fetchone()[0]=='unverified'


def test_tr_routes_never_fall_back_to_global():
    assert 'api.binance.me' in tr_kline_url(1)
    assert 'cloudme-tr.2meta.app' in tr_kline_url(3)
    with pytest.raises(ValueError):tr_kline_url(2)


def test_storage_quota_and_path_traversal(tmp_path):
    store=LocalStore(tmp_path,budget_bytes=3)
    store.put('a',b'123');store.put('a',b'123')
    with pytest.raises(QuotaExceeded):store.put('b',b'4')
    with pytest.raises(ValueError):store.put('../b',b'x')


def test_quota_reservation_does_not_exceed_soft_limit(state):
    db,_=state
    assert reserve(db,'2026-10','actions_minutes_month',1600)
    assert not reserve(db,'2026-10','actions_minutes_month',1)


def test_report_separates_inventory_and_history(state):
    from oran_cloud.report import coverage
    db,store=state;t=task(db);ingest_task(db,None,store,t,fetch_with(rows()))
    r=coverage(db,store)
    assert r['historical_symbols']==1 and r['totals']['rows']==1
    assert r['all_history_complete'] is False


def test_migrations_apply_and_signal_is_immutable(tmp_path):
    from pathlib import Path
    db=sqlite3.connect(tmp_path/'d1.sqlite')
    for p in sorted(Path('migrations').glob('*.sql')):db.executescript(p.read_text())
    db.execute("INSERT INTO model_versions VALUES('m',0,'challenger','models/m','{}')")
    db.execute("INSERT INTO signals VALUES('s','binance_tr:spot:BTC_TRY',1,'m',1,100,110,90,2,NULL,NULL,NULL,'paper_forward','{}')")
    with pytest.raises(sqlite3.IntegrityError):db.execute("UPDATE signals SET predicted_at=2 WHERE signal_id='s'")
    with pytest.raises(sqlite3.IntegrityError):db.execute("DELETE FROM signals")
    with pytest.raises(sqlite3.IntegrityError):db.execute("UPDATE model_versions SET artifact_key='other' WHERE model_id='m'")
    with pytest.raises(sqlite3.IntegrityError):db.execute("INSERT INTO signals VALUES('s2','x',1,'m',2,100,110,90,2,NULL,NULL,NULL,'paper_forward','{}')")
