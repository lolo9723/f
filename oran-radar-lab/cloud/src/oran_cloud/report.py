import datetime as dt
from .db import now_ms


def iso(value):
    return dt.datetime.fromtimestamp(value / 1000, dt.timezone.utc).isoformat() if value is not None else None


def coverage(db, store):
    inventory = {}
    for exchange in ('binance_global', 'binance_tr'):
        runs = db.execute('SELECT * FROM inventory_runs WHERE exchange=? ORDER BY observed_at DESC', (exchange,)).fetchall()
        inventory[exchange] = {
            'known_symbols': db.execute('SELECT COUNT(*) FROM symbols WHERE exchange=?', (exchange,)).fetchone()[0],
            'latest_status': runs[0]['status'] if runs else 'not_run',
            'latest_observed_at': iso(runs[0]['observed_at']) if runs else None,
            'sources': [dict(x) for x in runs[:4]],
        }
    totals = dict(db.execute('''SELECT COUNT(*) partitions,COALESCE(SUM(row_count),0) rows,
      COALESCE(SUM(bytes),0) parquet_bytes,MIN(first_ms) first_ms,MAX(last_ms) last_ms FROM raw_data_manifest WHERE status IN ('complete','partial') ''').fetchone())
    state = {r['status']:r['n'] for r in db.execute('SELECT status,COUNT(*) n FROM ingestion_tasks GROUP BY status')}
    verified = state.get('complete', 0)+state.get('superseded',0)
    planned = sum(state.values())
    intervals = [dict(x) for x in db.execute('''SELECT t.exchange,t.interval,COUNT(DISTINCT t.symbol) symbols,
      SUM(m.row_count) rows,MIN(m.first_ms) first_ms,MAX(m.last_ms) last_ms
      FROM raw_data_manifest m JOIN ingestion_tasks t USING(task_id) WHERE m.status IN ('complete','partial') GROUP BY t.exchange,t.interval''')]
    unresolved = db.execute('SELECT COUNT(*) FROM data_quality_issues WHERE resolved_at IS NULL').fetchone()[0]
    rejected=db.execute("SELECT COUNT(DISTINCT task_id) FROM data_quality_issues WHERE kind='invalid_rows' AND resolved_at IS NULL").fetchone()[0]
    files_valid=totals['partitions']
    known=sum(r['known_symbols'] for r in inventory.values())
    historical=db.execute('''SELECT COUNT(*) FROM
      (SELECT DISTINCT t.exchange,t.symbol FROM raw_data_manifest m JOIN ingestion_tasks t USING(task_id)
      WHERE m.status IN ('complete','partial'))''').fetchone()[0]
    return {
        'schema_version':'coverage-v1', 'generated_at':iso(now_ms()), 'generated_at_ms':now_ms(),
        'status':'partial' if planned or totals['rows'] else 'not_started',
        'scope':'All discovered spot symbols; history is complete only for individually verified tasks',
        'all_history_complete':False, 'inventory':inventory, 'tasks':state,
        'archive_catalog':{'files':db.execute('SELECT COUNT(*) FROM archive_catalog').fetchone()[0],
                           'scans':[dict(r) for r in db.execute('SELECT status,COUNT(*) scans FROM archive_catalog_scans GROUP BY status')]},
        'verified_planned_fraction':verified/planned if planned else None,
        'totals':{**totals,'first_utc':iso(totals['first_ms']),'last_utc':iso(totals['last_ms']),
                  'physical_object_bytes':store.used_bytes()},
        'intervals':intervals, 'unresolved_issues':unresolved,
        'historical_symbols':historical,'symbols_without_downloaded_history':known-historical,
        'quality':{'validated_source_partitions':files_valid,'rejected_source_partitions':rejected,
                   'downloaded_file_validation_rate':files_valid/(files_valid+rejected) if files_valid+rejected else None,
                   'complete_coverage_partitions':state.get('complete',0),
                   'definition':'Row/schema/hash validation; distinct from temporal coverage completeness.'},
        'failed_partitions':[dict(x) for x in db.execute('''SELECT exchange,symbol,interval,period,
          start_ms,end_ms,attempts,error FROM ingestion_tasks WHERE status='failed' LIMIT 1000''')],
        'unavailable_or_failed_symbols':db.execute('''SELECT COUNT(*) FROM
          (SELECT DISTINCT exchange,symbol FROM ingestion_tasks WHERE status='failed')''').fetchone()[0],
        'gaps':[dict(x) for x in db.execute('''SELECT * FROM data_quality_issues
          WHERE resolved_at IS NULL AND kind='missing_range' LIMIT 1000''')],
        'models':[dict(x) for x in db.execute('SELECT model_id,status,created_at FROM model_versions')],
        'signals':[dict(x) for x in db.execute('''SELECT signal_id,symbol_id,predicted_at,model_id,entry,target,stop,
          expires_at,score,probability,expected_return,mode FROM signals ORDER BY predicted_at DESC LIMIT 100''')],
        'jobs':[dict(x) for x in db.execute('SELECT * FROM system_jobs ORDER BY updated_at DESC LIMIT 100')],
        'limitations':['TR coverage requires a successful independent TR inventory and TR kline download.',
                      'No proven model or live trading performance is implied by ingestion completion.',
                      'SQLite/Parquet on a local disk is not continuously online cloud storage.'],
    }
