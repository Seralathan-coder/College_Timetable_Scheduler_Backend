-- Consecutive/Double Period scheduling: subjects can occupy N consecutive
-- periods per session (1 = default single-period behavior, 2 = double, 3 = triple).
ALTER TABLE subjects
    ADD COLUMN IF NOT EXISTS session_block_size INT NOT NULL DEFAULT 1;
