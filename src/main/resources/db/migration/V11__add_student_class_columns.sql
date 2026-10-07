-- ── V11: Role-based access — student class identity ──────────────────────
-- Adds nullable class identity columns for ROLE_STUDENT users so their
-- "own-class" timetable can be resolved (department → academic year → section).
-- Plain nullable FK-style ids (no constraint changes); non-student rows stay NULL.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS academic_year_id BIGINT DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS section_id       BIGINT DEFAULT NULL;

CREATE INDEX IF NOT EXISTS idx_users_section ON users (section_id);