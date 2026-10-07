-- Classroom ownership: nullable academic year / section references.
-- NULL means the room is global/shared; when set, the room is scoped to that
-- year / section. Existing rows are preserved untouched.
ALTER TABLE classrooms ADD COLUMN IF NOT EXISTS academic_year_id BIGINT REFERENCES academic_years(id) ON DELETE SET NULL;
ALTER TABLE classrooms ADD COLUMN IF NOT EXISTS section_id BIGINT REFERENCES sections(id) ON DELETE SET NULL;
