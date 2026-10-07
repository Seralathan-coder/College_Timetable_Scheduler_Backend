-- ============================================================
-- V3: Seed Data — Roles, Admin User, Time Slots, Sample Dept
-- ============================================================

-- ── Roles ────────────────────────────────────────────────────
INSERT INTO roles (name, description) VALUES
  ('ROLE_SUPER_ADMIN',       'Full system administrator'),
  ('ROLE_HOD',               'Head of Department'),
  ('ROLE_FACULTY',           'Teaching faculty member'),
  ('ROLE_EXAM_COORDINATOR',  'Exam and timetable coordinator')
ON CONFLICT (name) DO NOTHING;

-- ── Default Admin User ────────────────────────────────────────
-- Password: Admin@1234 (BCrypt $2a$12$...)
INSERT INTO users (username, email, password, full_name, is_active)
VALUES (
  'admin',
  'admin@college.edu',
  '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewsM9YRbhJ4iGrqO',
  'Super Administrator',
  TRUE
)
ON CONFLICT (username) DO NOTHING;

-- Assign SUPER_ADMIN role to admin user
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM   users u, roles r
WHERE  u.username = 'admin'
  AND  r.name     = 'ROLE_SUPER_ADMIN'
ON CONFLICT DO NOTHING;

-- ── Default Time Slots (8 periods + lunch) ────────────────────
INSERT INTO time_slots (slot_label, start_time, end_time, is_break, slot_order) VALUES
  ('P1',    '09:00', '09:50', FALSE, 1),
  ('P2',    '09:50', '10:40', FALSE, 2),
  ('P3',    '10:40', '11:30', FALSE, 3),
  ('P4',    '11:30', '12:20', FALSE, 4),
  ('LUNCH', '12:20', '13:10', TRUE,  5),
  ('P5',    '13:10', '14:00', FALSE, 6),
  ('P6',    '14:00', '14:50', FALSE, 7),
  ('P7',    '14:50', '15:40', FALSE, 8),
  ('P8',    '15:40', '16:30', FALSE, 9)
ON CONFLICT (slot_label) DO NOTHING;

-- ── Timetable Configuration for Current Session ───────────────
INSERT INTO timetable_configurations (academic_session, working_days, periods_per_day, is_saturday_working)
VALUES ('2025-26', 'MON,TUE,WED,THU,FRI', 8, FALSE)
ON CONFLICT (academic_session) DO NOTHING;

-- ── Sample Department (Computer Science & Engineering) ─────────
INSERT INTO departments (name, hod_name, contact_email, building, description)
VALUES (
  'Computer Science & Engineering',
  'Dr. Rajesh Kumar',
  'cse@college.edu',
  'Block A',
  'Department of Computer Science and Engineering'
)
ON CONFLICT (name) DO NOTHING;

-- ── Academic Years for CSE ────────────────────────────────────
INSERT INTO academic_years (department_id, year_label, is_enabled)
SELECT d.id, y.year_label, TRUE
FROM   departments d
CROSS JOIN (VALUES ('1st Year'), ('2nd Year'), ('3rd Year'), ('4th Year')) AS y(year_label)
WHERE  d.name = 'Computer Science & Engineering'
ON CONFLICT (department_id, year_label) DO NOTHING;

-- ── Default Sections for Academic Years ───────────────────────
INSERT INTO sections (academic_year_id, name, student_strength, status)
SELECT ay.id, s.name, 60, 'ACTIVE'
FROM   academic_years ay
CROSS JOIN (VALUES ('A'), ('B'), ('C')) AS s(name)
ON CONFLICT (academic_year_id, name) DO NOTHING;
