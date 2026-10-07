-- ============================================================
-- V1.1: Ensure Core Tables Exist (idempotent repair)
-- ============================================================
-- WHY THIS EXISTS
--   On a non-empty PostgreSQL schema, `spring.flyway.baseline-on-migrate: true`
--   caused Flyway to insert a BASELINE marker at version 1 and SKIP V1
--   entirely. V2 (create_timetable_schema) then failed with:
--       ERROR: relation "faculty" does not exist  (SQLSTATE 42P01)
--   because V2's FKs reference tables that only V1 creates.
--
--   This migration sits at version 1.1, i.e. strictly BETWEEN V1 (1) and V2 (2),
--   so it runs after V1 and BEFORE V2. That means it:
--     * on a fresh/empty schema  -> V1 already created everything, this is a no-op;
--     * on a baseline-at-1 schema -> V1 was skipped, so this materialises the
--                                   missing core tables before V2 needs them.
--
-- SAFETY
--   * Every statement is `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS`,
--     so it only CREATES MISSING objects and never alters or drops existing ones.
--   * The DDL below is reproduced VERBATIM from V1__create_core_schema.sql
--     (V1 is not modified; its checksum stays valid). Later additive migrations
--     (V4/V5/V7/V8/V11 columns, V12/V13/V14) run AFTER this and apply as usual.
--   * Named CHECK/UNIQUE constraints are only created when the table itself is
--     created, so they cannot collide with an already-present table.
--
-- Tables V2 depends on: roles, users, user_roles, departments, academic_years,
--                        faculty, sections, classrooms, subjects,
--                        subject_faculty_assignments
-- ============================================================

-- ── Roles ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS roles (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL UNIQUE,
    description VARCHAR(200)
);

-- ── Users ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id                    BIGSERIAL PRIMARY KEY,
    username              VARCHAR(100) NOT NULL UNIQUE,
    email                 VARCHAR(255) NOT NULL UNIQUE,
    password              VARCHAR(255) NOT NULL,
    full_name             VARCHAR(200) NOT NULL,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    refresh_token         VARCHAR(500),
    refresh_token_expiry  TIMESTAMP WITH TIME ZONE,
    last_login_at         TIMESTAMP WITH TIME ZONE,
    created_at            TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE,
    created_by            VARCHAR(100),
    updated_by            VARCHAR(100)
);

-- ── User ↔ Roles (Join) ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- ── Departments ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS departments (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(150) NOT NULL UNIQUE,
    hod_name      VARCHAR(150),
    contact_email VARCHAR(255),
    contact_phone VARCHAR(20),
    building      VARCHAR(100),
    description   TEXT,
    is_archived   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE,
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100)
);

-- ── Academic Years (I–IV per Department) ──────────────────────
CREATE TABLE IF NOT EXISTS academic_years (
    id            BIGSERIAL PRIMARY KEY,
    department_id BIGINT      NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
    year_label    VARCHAR(20) NOT NULL,   -- 'Year I', 'Year II', etc.
    is_enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE,
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    UNIQUE (department_id, year_label)
);

-- ── Faculty ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS faculty (
    id               BIGSERIAL PRIMARY KEY,
    employee_id      VARCHAR(50)  NOT NULL UNIQUE,
    first_name       VARCHAR(100) NOT NULL,
    last_name        VARCHAR(100) NOT NULL,
    email            VARCHAR(255) NOT NULL UNIQUE,
    phone            VARCHAR(20),
    department_id    BIGINT       REFERENCES departments(id) ON DELETE SET NULL,
    teaching_departments VARCHAR(500),
    designation      VARCHAR(100),
    qualification    VARCHAR(200),
    specialization   VARCHAR(200),
    max_daily_hours  INT          NOT NULL DEFAULT 6,
    max_weekly_hours INT          NOT NULL DEFAULT 24,
    status           VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    user_id          BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE,
    created_by       VARCHAR(100),
    updated_by       VARCHAR(100),
    CONSTRAINT chk_faculty_status CHECK (status IN ('AVAILABLE','BUSY','LEAVE'))
);

-- ── Sections ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sections (
    id                 BIGSERIAL PRIMARY KEY,
    academic_year_id   BIGINT      NOT NULL REFERENCES academic_years(id) ON DELETE CASCADE,
    name               VARCHAR(10) NOT NULL,
    student_strength   INT,
    faculty_advisor_id BIGINT      REFERENCES faculty(id) ON DELETE SET NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP WITH TIME ZONE,
    created_by         VARCHAR(100),
    updated_by         VARCHAR(100),
    UNIQUE (academic_year_id, name),
    CONSTRAINT chk_section_status CHECK (status IN ('ACTIVE','INACTIVE'))
);

-- ── Classrooms ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS classrooms (
    id            BIGSERIAL PRIMARY KEY,
    room_number   VARCHAR(20)  NOT NULL,
    room_name     VARCHAR(100),
    building      VARCHAR(100),
    department_id BIGINT       REFERENCES departments(id) ON DELETE SET NULL,
    room_type     VARCHAR(30)  NOT NULL DEFAULT 'LECTURE_HALL',
    capacity      INT          NOT NULL,
    floor         INT,
    status        VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    created_at    TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE,
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    UNIQUE (building, room_number),
    CONSTRAINT chk_room_type   CHECK (room_type IN ('LECTURE_HALL','LAB','SEMINAR_ROOM','AUDITORIUM')),
    CONSTRAINT chk_room_status CHECK (status IN ('AVAILABLE','RESERVED','MAINTENANCE'))
);

-- ── Subjects ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS subjects (
    id               BIGSERIAL PRIMARY KEY,
    subject_code     VARCHAR(30)  NOT NULL UNIQUE,
    subject_name     VARCHAR(200) NOT NULL,
    department_id    BIGINT       REFERENCES departments(id) ON DELETE SET NULL,
    academic_year_id BIGINT       REFERENCES academic_years(id) ON DELETE SET NULL,
    section_id       BIGINT       REFERENCES sections(id) ON DELETE SET NULL,
    semester         INT          NOT NULL,
    credits          INT          NOT NULL DEFAULT 3,
    theory_hours     INT          NOT NULL DEFAULT 0,
    practical_hours  INT          NOT NULL DEFAULT 0,
    subject_type     VARCHAR(20)  NOT NULL DEFAULT 'THEORY',
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE,
    created_by       VARCHAR(100),
    updated_by       VARCHAR(100),
    CONSTRAINT chk_subject_type CHECK (subject_type IN ('THEORY','LAB','ELECTIVE','MANDATORY'))
);

-- ── Subject ↔ Faculty Assignments ────────────────────────────
CREATE TABLE IF NOT EXISTS subject_faculty_assignments (
    id         BIGSERIAL PRIMARY KEY,
    subject_id BIGINT NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
    faculty_id BIGINT NOT NULL REFERENCES faculty(id) ON DELETE CASCADE,
    is_primary BOOLEAN NOT NULL DEFAULT TRUE,
    assigned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (subject_id, faculty_id)
);

-- ── Indexes ───────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_users_email         ON users(email);
CREATE INDEX IF NOT EXISTS idx_faculty_dept        ON faculty(department_id);
CREATE INDEX IF NOT EXISTS idx_faculty_status      ON faculty(status);
CREATE INDEX IF NOT EXISTS idx_sections_year       ON sections(academic_year_id);
CREATE INDEX IF NOT EXISTS idx_subjects_dept_year  ON subjects(department_id, academic_year_id);
CREATE INDEX IF NOT EXISTS idx_subjects_type       ON subjects(subject_type);
CREATE INDEX IF NOT EXISTS idx_classrooms_type     ON classrooms(room_type, status);
CREATE INDEX IF NOT EXISTS idx_academic_years_dept ON academic_years(department_id);
