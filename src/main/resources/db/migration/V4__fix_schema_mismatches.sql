-- ============================================================
-- V4: Fix entity-to-schema mismatches
-- Adds missing columns that JPA entities expect but V1/V2 did not define
-- ============================================================

-- faculty: add assigned_subject_codes (comma-separated subject codes)
ALTER TABLE faculty
  ADD COLUMN IF NOT EXISTS assigned_subject_codes VARCHAR(500);

-- subjects: add assigned_faculty_id, total_semester_hours, teaching_weeks
ALTER TABLE subjects
  ADD COLUMN IF NOT EXISTS assigned_faculty_id BIGINT REFERENCES faculty(id) ON DELETE SET NULL;

ALTER TABLE subjects
  ADD COLUMN IF NOT EXISTS total_semester_hours INT NOT NULL DEFAULT 45;

ALTER TABLE subjects
  ADD COLUMN IF NOT EXISTS teaching_weeks INT NOT NULL DEFAULT 15;

-- users: add department_id
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS department_id BIGINT REFERENCES departments(id) ON DELETE SET NULL;

-- timetable_conflicts: add severity, entry_id_1, entry_id_2, is_resolved
ALTER TABLE timetable_conflicts
  ADD COLUMN IF NOT EXISTS severity VARCHAR(20) NOT NULL DEFAULT 'HIGH';

ALTER TABLE timetable_conflicts
  ADD COLUMN IF NOT EXISTS entry_id_1 BIGINT REFERENCES timetable_entries(id) ON DELETE SET NULL;

ALTER TABLE timetable_conflicts
  ADD COLUMN IF NOT EXISTS entry_id_2 BIGINT REFERENCES timetable_entries(id) ON DELETE SET NULL;

ALTER TABLE timetable_conflicts
  ADD COLUMN IF NOT EXISTS is_resolved BOOLEAN NOT NULL DEFAULT FALSE;

-- Indexes for new columns
CREATE INDEX IF NOT EXISTS idx_subjects_faculty ON subjects(assigned_faculty_id);
CREATE INDEX IF NOT EXISTS idx_users_department ON users(department_id);
