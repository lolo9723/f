CREATE TABLE IF NOT EXISTS snapshots(id text PRIMARY KEY,generated_at bigint NOT NULL,payload_json text NOT NULL);
CREATE TABLE IF NOT EXISTS user_settings(user_id text PRIMARY KEY,settings_json text NOT NULL,updated_at bigint NOT NULL);
CREATE TABLE IF NOT EXISTS api_tokens(token_hash text PRIMARY KEY,user_id text NOT NULL,created_at bigint NOT NULL,expires_at bigint NOT NULL,revoked_at bigint);
CREATE INDEX IF NOT EXISTS token_user ON api_tokens(user_id);
CREATE TABLE IF NOT EXISTS devices(device_id text PRIMARY KEY,user_id text NOT NULL,fcm_token text NOT NULL UNIQUE,updated_at bigint NOT NULL);
CREATE TABLE IF NOT EXISTS notification_outbox(delivery_id text PRIMARY KEY,signal_id text NOT NULL,device_id text NOT NULL,status text NOT NULL,attempts integer NOT NULL DEFAULT 0,updated_at bigint NOT NULL,UNIQUE(signal_id,device_id));
CREATE TABLE IF NOT EXISTS system_jobs(id text PRIMARY KEY,kind text NOT NULL,due_at bigint NOT NULL,status text NOT NULL,updated_at bigint NOT NULL,details_json text NOT NULL);
CREATE INDEX IF NOT EXISTS jobs_due ON system_jobs(status,due_at);
CREATE TABLE IF NOT EXISTS live_state(id text PRIMARY KEY,payload jsonb NOT NULL,updated_at bigint NOT NULL);
CREATE TABLE IF NOT EXISTS live_runs(id text PRIMARY KEY,started_at bigint NOT NULL,finished_at bigint,status text NOT NULL,details jsonb NOT NULL DEFAULT '{}');
CREATE TABLE IF NOT EXISTS live_candles(exchange text NOT NULL,symbol text NOT NULL,interval text NOT NULL,open_ms bigint NOT NULL,payload jsonb NOT NULL,PRIMARY KEY(exchange,symbol,interval,open_ms));
CREATE TABLE IF NOT EXISTS live_patterns(id text PRIMARY KEY,symbol text NOT NULL,observed_at bigint NOT NULL,features jsonb NOT NULL,price double precision NOT NULL,outcomes jsonb NOT NULL DEFAULT '{}');
CREATE TABLE IF NOT EXISTS wallets(user_id text PRIMARY KEY,revision integer NOT NULL,document jsonb NOT NULL,updated_at bigint NOT NULL);
CREATE TABLE IF NOT EXISTS engine_lock(id text PRIMARY KEY,lease_until bigint NOT NULL,owner text NOT NULL);

CREATE TABLE IF NOT EXISTS live_models(pair text PRIMARY KEY,model_id text NOT NULL,payload jsonb NOT NULL,updated_at bigint NOT NULL);
CREATE TABLE IF NOT EXISTS live_model_versions(model_id text PRIMARY KEY,pair text NOT NULL,created_at bigint NOT NULL,metadata jsonb NOT NULL);
