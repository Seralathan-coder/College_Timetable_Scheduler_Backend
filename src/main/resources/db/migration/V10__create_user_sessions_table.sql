-- ── V10: Multi-device sessions (Phase 5) ─────────────────────────────
-- Routes refresh tokens through per-login `user_sessions` rows instead of
-- the single `users.refresh_token` column, enabling multiple simultaneous
-- logins and logout-from-other-devices.
--
-- The two `users` refresh-token columns are deliberately LEFT in place:
-- dropping them would rewrite the `users` table for zero behavioural gain,
-- and their absence would complicate rollbacks. They are simply no longer
-- read or written by the application.

CREATE TABLE IF NOT EXISTS user_sessions (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token VARCHAR(500) NOT NULL,
    device_info   VARCHAR(300),
    ip_address    VARCHAR(45),
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    is_revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE,
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_user_sessions_user_id ON user_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_user_sessions_refresh_token ON user_sessions(refresh_token);