-- ── V8: Profile & Account Management — user profile columns ────────────
-- Adds per-user profile fields used by the "My Profile" feature.
-- Institution name/address live in a single global `institution` table (Phase 3).
-- Multi-device sessions live in a `user_sessions` table (Phase 5).

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS phone             VARCHAR(20)  DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS profile_photo_url VARCHAR(500) DEFAULT NULL;