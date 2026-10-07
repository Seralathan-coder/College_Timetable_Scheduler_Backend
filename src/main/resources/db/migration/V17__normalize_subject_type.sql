-- Subject Type master values.
--
-- The supported set is now THEORY / LAB / GAME / OTHER. The legacy
-- ELECTIVE and MANDATORY values were never read by any scheduling or
-- business logic - a subject's periods are derived from its theory/practical
-- hours, not from these labels - so the two retired values are folded into
-- THEORY before the constraint is tightened. No subject row is deleted and no
-- other column is touched.
--
-- Safe on existing data: the UPDATE only relabels the two retired literals,
-- then the old CHECK (THEORY/LAB/ELECTIVE/MANDATORY) is replaced by the new
-- one. IF EXISTS keeps this idempotent for databases whose subject_type CHECK
-- was never created.
UPDATE subjects SET subject_type = 'THEORY' WHERE subject_type IN ('ELECTIVE', 'MANDATORY');

ALTER TABLE subjects DROP CONSTRAINT IF EXISTS chk_subject_type;

ALTER TABLE subjects
    ADD CONSTRAINT chk_subject_type
    CHECK (subject_type IN ('THEORY', 'LAB', 'GAME', 'OTHER'));
