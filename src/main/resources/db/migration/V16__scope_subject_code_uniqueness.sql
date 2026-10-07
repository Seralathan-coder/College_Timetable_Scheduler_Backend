-- Subject code is no longer globally unique.
--
-- Business rule: within a department a subject code maps to exactly ONE academic
-- year. It MAY be repeated across the different SECTIONS of that year (e.g. CS266
-- to sections A, B and C of 1st year), but must NOT appear in another year
-- (2nd/3rd/4th) of the same department, while another department is a separate
-- code space. That year dependency is NOT expressible as a single SQL unique
-- constraint, so it is enforced in SubjectService.createSubject/updateSubject.
--
-- Here we drop the stale global UNIQUE and keep a plain (non-unique) index for
-- subject-code lookups and search.
--
-- Safe on existing data: dropping a constraint never touches rows. The original
-- constraint is the PostgreSQL auto-name for the inline column UNIQUE declared in
-- V1/V1.1 (`subjects_subject_code_key`); IF EXISTS keeps this migration
-- idempotent on databases whose subject_code was never globally unique.
ALTER TABLE subjects DROP CONSTRAINT IF EXISTS subjects_subject_code_key;

CREATE INDEX IF NOT EXISTS idx_subjects_code ON subjects (subject_code);
