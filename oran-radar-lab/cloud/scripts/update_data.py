"""Bounded job runner. Persistent local disk OR explicitly approved R2 is mandatory."""
import datetime as dt
import fcntl
import json
import os
import sqlite3
from pathlib import Path
import requests
from oran_cloud.db import connect,now_ms,identity
from oran_cloud.cli import make_store
from oran_cloud.http import Http
from oran_cloud.sources import discover,catalog_batch
from oran_cloud.ingestion import plan,plan_recent,run
from oran_cloud.report import coverage

root=Path(os.environ.get('ORAN_DATA','data'));root.mkdir(parents=True,exist_ok=True)
lock=open(root/'.writer.lock','w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
r2=os.environ.get('ORAN_R2_APPROVED')=='true'
if os.environ.get('GITHUB_ACTIONS')=='true' and not r2 and os.environ.get('ORAN_PERSISTENT_RUNNER')!='true':
    raise SystemExit('Ephemeral runner without durable storage refused. Use persistent runner or approved R2.')

remote=None; etag=None
if r2:
    from oran_cloud.store import R2Store
    from botocore.exceptions import ClientError
    remote=R2Store(os.environ['ORAN_R2_ENDPOINT'],os.environ['ORAN_R2_BUCKET'])
    try:
        obj=remote.client.get_object(Bucket=remote.bucket,Key='control/metadata.sqlite')
        (root/'metadata.sqlite').write_bytes(obj['Body'].read());etag=obj['ETag']
    except ClientError as e:
        if e.response['Error']['Code'] not in ('NoSuchKey','404'):raise

db=connect(root/'metadata.sqlite');store=make_store(root,db);http=Http()
job_id=identity('update',now_ms())
with db:db.execute('INSERT INTO system_jobs VALUES(?,?,?, ?,?,?)',(job_id,'bounded_update',now_ms(),'running',now_ms(),'{}'))
failed=False
try:
    discovered=discover(db,http)
    symbols=[dict(x) for x in db.execute('SELECT * FROM symbols')]
    # Plan only files actually listed; avoid millions of speculative pre-listing 404s.
    last_month=(dt.date.today().replace(day=1)-dt.timedelta(days=1)).strftime('%Y-%m')
    catalog_batch(db,http,max_scans=int(os.environ.get('ORAN_CATALOG_BATCH','100')))
    for r in db.execute('SELECT symbol,interval,period FROM archive_catalog').fetchall():
        plan(db,[{'exchange':'binance_global','symbol':r['symbol']}],[r['interval']],r['period'],r['period'])
    tr_all=[s for s in symbols if s['exchange']=='binance_tr']
    # TR REST has no Global archive substitute; unknown listing dates remain explicit.
    if tr_all:plan(db,tr_all,['1d','1h'],'2017-07',last_month)
    tr=[s for s in symbols if s['exchange']=='binance_tr' and s['status']=='enabled']
    plan_recent(db,symbols,['1d','1h'],days=3)
    if tr:plan_recent(db,tr,['1m'],days=3)
    result=run(db,http,store,max_files=int(os.environ.get('ORAN_BATCH_FILES','350')),max_seconds=720)
    failed=result['failed']>0 or any(x['status']=='failed' for x in discovered.values())
except Exception:
    failed=True
    raise
finally:
    with db:db.execute('UPDATE system_jobs SET status=?,updated_at=? WHERE id=?',('partial' if failed else 'complete',now_ms(),job_id))
    report=coverage(db,store)
    reports=Path('reports');reports.mkdir(exist_ok=True)
    (reports/'coverage.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    # SQLite backup makes a consistent single object; WAL bytes are never uploaded separately.
    if remote:
        backup_path=root/'metadata-backup.sqlite';backup=sqlite3.connect(backup_path)
        db.backup(backup);backup.close()
        guard={'IfMatch':etag} if etag else {'IfNoneMatch':'*'}
        remote.client.put_object(Bucket=remote.bucket,Key='control/metadata.sqlite',Body=backup_path.read_bytes(),**guard)
        # CAS failure stops publication; immutable Parquet objects remain recoverable.
    api=os.environ.get('ORAN_API_URL','').rstrip('/')
    secret=os.environ.get('ORAN_INGEST_TOKEN')
    if api and secret:
        if not api.startswith('https://'):raise ValueError('HTTPS API required')
        r=requests.put(api+'/v1/ingest/snapshot',json=report,headers={'Authorization':'Bearer '+secret},timeout=30)
        r.raise_for_status()
    db.close()
print(json.dumps({'job_id':job_id,'partial':failed,'report':'reports/coverage.json'}))
