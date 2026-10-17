"""Offline dataset import. Structural checks are not a source-credibility verdict."""
import csv
import hashlib
import io
import json
from pathlib import Path
from .db import now_ms, identity


def import_dataset(db,store,path,metadata,mapping):
    required={'source','methodology','created_at','as_of','version'}
    if not required<=metadata.keys() or any(not metadata[k] for k in required):
        raise ValueError('source/methodology/created_at/as_of/version are required')
    if not isinstance(metadata['as_of'],int) or not isinstance(metadata['created_at'],int) or metadata['as_of']>now_ms():
        raise ValueError('metadata timestamps must be UTC milliseconds, not in the future')
    p=Path(path); blob=p.read_bytes()
    if len(blob)>25_000_000:
        raise ValueError('import exceeds 25 MB; split into bounded parts')
    if p.suffix.lower()=='.csv':
        rows=list(csv.DictReader(io.StringIO(blob.decode('utf-8-sig'))))
    elif p.suffix.lower()=='.parquet':
        import pyarrow.parquet as pq
        rows=pq.read_table(io.BytesIO(blob)).to_pylist()
    elif p.suffix.lower()=='.xlsx':
        import openpyxl
        wb=openpyxl.load_workbook(io.BytesIO(blob),read_only=True,data_only=True)
        values=iter(wb.active.values); header=next(values)
        rows=[dict(zip(header,r)) for r in values]; wb.close()
    else:
        raise ValueError('supported input: CSV, XLSX, Parquet')
    checked=[]
    for i,row in enumerate(rows):
        r={dest:row[src] for dest,src in mapping.items()}
        as_of=int(r['as_of']); event=int(r['event_time'])
        if as_of>event or as_of>metadata['as_of']:
            raise ValueError(f'row {i+1}: future information / as_of mismatch')
        checked.append(r)
    if not checked:
        raise ValueError('empty import')
    normalized=json.dumps(checked,ensure_ascii=False,sort_keys=True,default=str).encode()
    digest=hashlib.sha256(blob).hexdigest(); dataset_id=identity(digest,metadata,mapping)
    key=f'imports/{dataset_id}.json'; store.put(key,normalized)
    with db:
        db.execute('INSERT OR IGNORE INTO imported_datasets VALUES(?,?,?,?,?,?,?,?,?)',
                   (dataset_id,metadata['source'],metadata['methodology'],metadata['created_at'],
                    metadata['as_of'],metadata['version'],'structurally_valid_pending_source_review',key,digest))
    return {'dataset_id':dataset_id,'rows':len(checked),'quality_status':'structurally_valid_pending_source_review',
            'training_eligible':False}
