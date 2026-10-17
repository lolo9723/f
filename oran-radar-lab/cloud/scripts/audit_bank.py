"""Full read audit of verified Parquet objects; no download or data mutation."""
import hashlib,json,sqlite3
from pathlib import Path
from oran_cloud.candles import read_parquet
from oran_cloud.store import LocalStore
from oran_cloud.db import now_ms

root=Path('data');db=sqlite3.connect(root/'metadata.sqlite');db.row_factory=sqlite3.Row
store=LocalStore(root/'objects');keys=set();rows=0;failures=[];partitions=0
for m in db.execute("SELECT m.*,t.exchange,t.symbol,t.interval FROM raw_data_manifest m JOIN ingestion_tasks t USING(task_id) WHERE m.status IN ('complete','partial')"):
    try:
        blob=store.get(m['object_key']);assert hashlib.sha256(blob).hexdigest()==m['parquet_sha256']
        candles=read_parquet(blob);assert len(candles)==m['row_count']
        assert candles[0].open_ms==m['first_ms'] and candles[-1].close_ms==m['last_ms']
        for c in candles:
            k=(m['exchange'],m['symbol'],m['interval'],c.open_ms)
            assert k not in keys, 'duplicate logical candle'
            keys.add(k)
        rows+=len(candles);partitions+=1
    except Exception as e:failures.append({'task_id':m['task_id'],'error':str(e)})
report={'audited_at_ms':now_ms(),'verified_partitions_read':partitions,'verified_rows_read':rows,
        'unique_logical_candles':len(keys),'hash_or_row_failures':failures,
        'scope':'Verified manifest entries only; rejected/missing files and entire universe are excluded.'}
Path('reports/bank-audit.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2));assert not failures
