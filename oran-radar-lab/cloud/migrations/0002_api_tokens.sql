CREATE TABLE IF NOT EXISTS api_tokens (
 token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL,created_at INTEGER NOT NULL,
 expires_at INTEGER NOT NULL,revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS token_user ON api_tokens(user_id);
CREATE INDEX IF NOT EXISTS jobs_due ON system_jobs(status,due_at);
