-- ── V9: Institution — single global college name/address (Phase 3) ──────
-- Single-row table (id = 1). Readable by all authenticated users,
-- editable only by SUPER_ADMIN (enforced at the service/controller level).

CREATE TABLE IF NOT EXISTS institution (
    id         BIGINT PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    address    TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- Guarantee exactly one row (the global institution). id = 1 is enforced
-- here AND in the application (Institution.SINGLETON_ID).
INSERT INTO institution (id, name)
SELECT 1, 'Default Institution'
WHERE NOT EXISTS (SELECT 1 FROM institution WHERE id = 1);