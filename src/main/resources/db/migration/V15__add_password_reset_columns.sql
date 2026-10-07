-- ── V15: Forgot / Reset password (Faculty access overhaul) ─────────────
-- Holds a one-time password-reset token per user. Only the SHA-256 hash of
-- the returned token is stored (never the raw token), with an expiry so stale
-- resets die even if nobody completes them. h2 dev DBs pick these columns up
-- via `ddl-auto: update`; this migration covers the Postgres (Flyway) path.

ALTER TABLE users ADD COLUMN IF NOT EXISTS password_reset_token VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_reset_expiry TIMESTAMP WITH TIME ZONE;