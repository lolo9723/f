import argparse
import contextlib
import fcntl
import json
import os
from pathlib import Path
from .db import connect
from .http import Http
from .sources import discover
from .store import LocalStore, R2Store
from .ingestion import plan, plan_recent, run
from .report import coverage


def make_store(root, db=None):
    if os.environ.get('ORAN_R2_APPROVED') == 'true':
        return R2Store(os.environ['ORAN_R2_ENDPOINT'], os.environ['ORAN_R2_BUCKET'], ledger=db)
    return LocalStore(root / 'objects')


def main():
    parser = argparse.ArgumentParser(description='Oran Radar Cloud research CLI')
    parser.add_argument('--data', default='data')
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('discover')
    for name in ['plan', 'recent']:
        p = sub.add_parser(name)
        p.add_argument('--exchange', choices=['binance_global','binance_tr','all'], default='all')
        p.add_argument('--symbols', help='comma separated; omitted = all discovered')
        p.add_argument('--intervals', default='1d,1h')
        if name == 'plan':
            p.add_argument('--start', default='2017-07'); p.add_argument('--end', required=True)
        else:
            p.add_argument('--days', type=int, default=3)
    p = sub.add_parser('run'); p.add_argument('--max-files', type=int, default=12); p.add_argument('--max-seconds', type=int, default=240)
    p = sub.add_parser('report'); p.add_argument('--out', default='reports/coverage.json')
    p = sub.add_parser('import'); p.add_argument('file'); p.add_argument('--metadata', required=True); p.add_argument('--mapping', required=True)
    p = sub.add_parser('research'); p.add_argument('--exchange', choices=['binance_global','binance_tr'],required=True); p.add_argument('--symbol',required=True); p.add_argument('--out',default='reports/research.json')
    root = Path(parser.parse_args().data); root.mkdir(parents=True, exist_ok=True)
    args = parser.parse_args()
    with open(root / '.writer.lock', 'w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            parser.error('another writer holds this data directory')
        db = connect(root / 'metadata.sqlite'); store = make_store(root,db); http = Http()
        if args.command == 'discover':
            result = discover(db, http)
        elif args.command in ('plan','recent'):
            records = [dict(x) for x in db.execute('SELECT * FROM symbols')
                       if args.exchange in ('all',x['exchange']) and
                       (not args.symbols or x['symbol'] in args.symbols.split(','))]
            if not records:
                parser.error('no matching inventory; run discover successfully first')
            if args.command == 'plan':
                result = {'new_tasks':plan(db, records,args.intervals.split(','),args.start,args.end)}
            else:
                result = {'new_tasks':plan_recent(db, records,args.intervals.split(','),args.days)}
        elif args.command == 'run':
            result = run(db,http,store,args.max_files,args.max_seconds)
        elif args.command == 'import':
            from .imports import import_dataset
            result=import_dataset(db,store,args.file,json.loads(Path(args.metadata).read_text()),json.loads(Path(args.mapping).read_text()))
        elif args.command == 'research':
            from .experiments import train_candidate
            result=train_candidate(db,store,args.exchange,args.symbol)
            out=Path(args.out);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(result,ensure_ascii=False,indent=2))
        else:
            result = coverage(db, store)
            out = Path(args.out); out.parent.mkdir(parents=True,exist_ok=True)
            out.write_text(json.dumps(result,ensure_ascii=False,indent=2))
        print(json.dumps(result,ensure_ascii=False,indent=2))
        db.close()


if __name__ == '__main__':
    main()
