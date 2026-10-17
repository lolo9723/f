PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS assets (
 asset_id TEXT PRIMARY KEY, exchange TEXT NOT NULL, ticker TEXT NOT NULL,
 identity_status TEXT NOT NULL DEFAULT 'exchange_ticker_only', UNIQUE(exchange,ticker)
);
CREATE TABLE IF NOT EXISTS markets (market_id TEXT PRIMARY KEY, exchange TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind='spot'));
CREATE TABLE IF NOT EXISTS symbols (
 symbol_id TEXT PRIMARY KEY, exchange TEXT NOT NULL, symbol TEXT NOT NULL,
 base_asset TEXT, quote_asset TEXT, status TEXT NOT NULL, symbol_type INTEGER,
 rules_json TEXT NOT NULL, source TEXT NOT NULL, first_seen INTEGER NOT NULL,
 last_seen INTEGER NOT NULL, history_status TEXT NOT NULL DEFAULT 'unverified',
 UNIQUE(exchange,symbol)
);
CREATE INDEX IF NOT EXISTS symbols_exchange ON symbols(exchange,quote_asset);
CREATE TABLE IF NOT EXISTS listing_events (
 id TEXT PRIMARY KEY, symbol_id TEXT NOT NULL REFERENCES symbols(symbol_id),
 observed_at INTEGER NOT NULL, event TEXT NOT NULL, source TEXT NOT NULL,
 effective_at INTEGER
);
CREATE TABLE IF NOT EXISTS inventory_runs (
 id TEXT PRIMARY KEY, exchange TEXT NOT NULL, source TEXT NOT NULL,
 observed_at INTEGER NOT NULL, status TEXT NOT NULL, count INTEGER, error TEXT
);
CREATE TABLE IF NOT EXISTS archive_catalog (
 exchange TEXT NOT NULL, symbol TEXT NOT NULL, interval TEXT NOT NULL,
 period TEXT NOT NULL, source_url TEXT NOT NULL, observed_at INTEGER NOT NULL,
 PRIMARY KEY(exchange,symbol,interval,period)
);
CREATE TABLE IF NOT EXISTS archive_catalog_scans (
 symbol TEXT NOT NULL, interval TEXT NOT NULL, checked_at INTEGER NOT NULL,
 status TEXT NOT NULL, error TEXT, PRIMARY KEY(symbol,interval)
);
CREATE TABLE IF NOT EXISTS ingestion_tasks (
 task_id TEXT PRIMARY KEY, exchange TEXT NOT NULL, symbol TEXT NOT NULL,
 interval TEXT NOT NULL, period TEXT NOT NULL, start_ms INTEGER NOT NULL,
 end_ms INTEGER NOT NULL, source TEXT NOT NULL, priority INTEGER NOT NULL DEFAULT 100,
 status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
 retry_after INTEGER NOT NULL DEFAULT 0, error TEXT, updated_at INTEGER NOT NULL,
 UNIQUE(exchange,symbol,interval,period)
);
CREATE INDEX IF NOT EXISTS task_queue ON ingestion_tasks(status,retry_after,priority);
CREATE TABLE IF NOT EXISTS raw_data_manifest (
 task_id TEXT PRIMARY KEY REFERENCES ingestion_tasks(task_id), object_key TEXT NOT NULL UNIQUE,
 source_url TEXT NOT NULL, source_sha256 TEXT NOT NULL, parquet_sha256 TEXT NOT NULL,
 row_count INTEGER NOT NULL, bytes INTEGER NOT NULL, first_ms INTEGER NOT NULL,
 last_ms INTEGER NOT NULL, status TEXT NOT NULL, validated_at INTEGER NOT NULL,
 timestamp_unit TEXT NOT NULL, schema_version TEXT NOT NULL DEFAULT 'ohlcv-v1'
);
CREATE TABLE IF NOT EXISTS ingestion_checkpoints (
 exchange TEXT NOT NULL, symbol TEXT NOT NULL, interval TEXT NOT NULL,
 last_verified_ms INTEGER NOT NULL, updated_at INTEGER NOT NULL,
 PRIMARY KEY(exchange,symbol,interval)
);
CREATE TABLE IF NOT EXISTS data_quality_issues (
 issue_id TEXT PRIMARY KEY, task_id TEXT NOT NULL, kind TEXT NOT NULL,
 start_ms INTEGER, end_ms INTEGER, detail TEXT NOT NULL,
 created_at INTEGER NOT NULL, resolved_at INTEGER
);
CREATE TABLE IF NOT EXISTS imported_datasets (
 dataset_id TEXT PRIMARY KEY, source TEXT NOT NULL, methodology TEXT NOT NULL,
 created_at INTEGER NOT NULL, as_of INTEGER NOT NULL, version TEXT NOT NULL,
 quality_status TEXT NOT NULL, object_key TEXT NOT NULL, sha256 TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS feature_sets (
 id TEXT PRIMARY KEY, version TEXT NOT NULL, source_manifest_hash TEXT NOT NULL,
 as_of INTEGER NOT NULL, object_key TEXT NOT NULL, windows_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pattern_events (
 id TEXT PRIMARY KEY, symbol_id TEXT NOT NULL, observed_at INTEGER NOT NULL,
 feature_version TEXT NOT NULL, features_json TEXT NOT NULL, regime TEXT NOT NULL,
 source_manifest_hash TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pattern_outcomes (
 event_id TEXT NOT NULL, horizon_h INTEGER NOT NULL, outcome_json TEXT NOT NULL,
 evaluated_at INTEGER NOT NULL, PRIMARY KEY(event_id,horizon_h)
);
CREATE TABLE IF NOT EXISTS model_versions (
 model_id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, status TEXT NOT NULL,
 artifact_key TEXT NOT NULL, metadata_json TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS one_champion ON model_versions(status) WHERE status='champion';
CREATE TRIGGER IF NOT EXISTS model_content_immutable
 BEFORE UPDATE OF model_id,created_at,artifact_key,metadata_json ON model_versions
 BEGIN SELECT RAISE(ABORT,'create a new model version'); END;
CREATE TABLE IF NOT EXISTS training_runs (
 id TEXT PRIMARY KEY, started_at INTEGER NOT NULL, status TEXT NOT NULL,
 model_id TEXT, metrics_json TEXT NOT NULL, dataset_hash TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS signals (
 signal_id TEXT PRIMARY KEY, symbol_id TEXT NOT NULL, predicted_at INTEGER NOT NULL,
 model_id TEXT NOT NULL REFERENCES model_versions(model_id), feature_as_of INTEGER NOT NULL,
 entry REAL NOT NULL CHECK(entry>0), target REAL NOT NULL, stop REAL NOT NULL,
 expires_at INTEGER NOT NULL, score REAL, probability REAL, expected_return REAL,
 mode TEXT NOT NULL CHECK(mode IN ('paper_forward','historical_simulation')),
 payload_json TEXT NOT NULL, CHECK(feature_as_of<=predicted_at),
 CHECK(stop<entry AND target>entry), CHECK(expires_at>predicted_at)
);
CREATE TRIGGER IF NOT EXISTS signals_no_update BEFORE UPDATE ON signals BEGIN SELECT RAISE(ABORT,'signals are immutable'); END;
CREATE TRIGGER IF NOT EXISTS signals_no_delete BEFORE DELETE ON signals BEGIN SELECT RAISE(ABORT,'archive signals; never delete'); END;
CREATE TABLE IF NOT EXISTS signal_outcomes (
 signal_id TEXT NOT NULL REFERENCES signals(signal_id), horizon_h INTEGER NOT NULL,
 evaluated_at INTEGER NOT NULL, status TEXT NOT NULL, payload_json TEXT NOT NULL,
 PRIMARY KEY(signal_id,horizon_h)
);
CREATE TABLE IF NOT EXISTS backtest_runs (
 id TEXT PRIMARY KEY, model_id TEXT NOT NULL, created_at INTEGER NOT NULL,
 config_json TEXT NOT NULL, metrics_json TEXT NOT NULL, dataset_hash TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS system_jobs (
 id TEXT PRIMARY KEY, kind TEXT NOT NULL, due_at INTEGER NOT NULL,
 status TEXT NOT NULL, updated_at INTEGER NOT NULL, details_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS usage_metrics (
 period TEXT NOT NULL, resource TEXT NOT NULL, used INTEGER NOT NULL,
 max_allowed INTEGER NOT NULL, observed_at INTEGER NOT NULL,
 PRIMARY KEY(period,resource)
);
CREATE TABLE IF NOT EXISTS user_settings (
 user_id TEXT PRIMARY KEY, settings_json TEXT NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
 device_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, fcm_token TEXT NOT NULL UNIQUE,
 updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS notification_outbox (
 delivery_id TEXT PRIMARY KEY, signal_id TEXT NOT NULL, device_id TEXT NOT NULL,
 status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL,
 UNIQUE(signal_id,device_id)
);
CREATE TABLE IF NOT EXISTS snapshots (
 id TEXT PRIMARY KEY, generated_at INTEGER NOT NULL, payload_json TEXT NOT NULL
);
