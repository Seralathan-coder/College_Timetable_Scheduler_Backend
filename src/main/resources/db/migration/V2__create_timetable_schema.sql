-- ============================================================
-- V2: Timetable Schema — Availability, Slots, Timetable, Entries
-- ============================================================

-- ── Time Slots (P1–P8, Lunch) ────────────────────────────────
CREATE TABLE IF NOT EXISTS time_slots (
    id          BIGSERIAL PRIMARY KEY,
    slot_label  VARCHAR(10)  NOT NULL UNIQUE,
    start_time  TIME         NOT NULL,
    end_time    TIME         NOT NULL,
    is_break    BOOLEAN      NOT NULL DEFAULT FALSE,
    slot_order  INT          NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW()
);

-- ── Faculty Availability Matrix ───────────────────────────────
CREATE TABLE IF NOT EXISTS faculty_availability (
    id           BIGSERIAL PRIMARY KEY,
    faculty_id   BIGINT      NOT NULL REFERENCES faculty(id) ON DELETE CASCADE,
    day_of_week  VARCHAR(10) NOT NULL,
    time_slot_id BIGINT      NOT NULL REFERENCES time_slots(id) ON DELETE CASCADE,
    slot_type    VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP WITH TIME ZONE,
    UNIQUE (faculty_id, day_of_week, time_slot_id),
    CONSTRAINT chk_day_of_week CHECK (day_of_week IN ('MON','TUE','WED','THU','FRI','SAT')),
    CONSTRAINT chk_slot_type   CHECK (slot_type   IN ('AVAILABLE','PREFERRED','BLOCKED','BUSY'))
);

-- ── Timetable Configuration ───────────────────────────────────
CREATE TABLE IF NOT EXISTS timetable_configurations (
    id                  BIGSERIAL PRIMARY KEY,
    academic_session    VARCHAR(20)  NOT NULL UNIQUE,  -- '2025-26'
    working_days        VARCHAR(100) NOT NULL DEFAULT 'MON,TUE,WED,THU,FRI',
    periods_per_day     INT          NOT NULL DEFAULT 8,
    lunch_slot_id       BIGINT       REFERENCES time_slots(id),
    is_saturday_working BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100)
);

-- ── Timetable Header ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS timetables (
    id               BIGSERIAL PRIMARY KEY,
    department_id    BIGINT      NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
    academic_year_id BIGINT      REFERENCES academic_years(id) ON DELETE CASCADE,
    section_id       BIGINT      REFERENCES sections(id) ON DELETE CASCADE,
    semester         INT         NOT NULL,
    academic_session VARCHAR(50) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    conflict_count   INT         NOT NULL DEFAULT 0,
    optimization_score INT       NOT NULL DEFAULT 100,
    generated_at     TIMESTAMP WITH TIME ZONE,
    published_at     TIMESTAMP WITH TIME ZONE,
    created_by_id    BIGINT      REFERENCES users(id) ON DELETE SET NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE,
    created_by       VARCHAR(100),
    updated_by       VARCHAR(100),
    CONSTRAINT chk_timetable_status CHECK (status IN ('DRAFT','GENERATED','PUBLISHED','ARCHIVED'))
);

-- ── Timetable Entries (the actual schedule) ───────────────────
CREATE TABLE IF NOT EXISTS timetable_entries (
    id            BIGSERIAL PRIMARY KEY,
    timetable_id  BIGINT      NOT NULL REFERENCES timetables(id) ON DELETE CASCADE,
    section_id    BIGINT      NOT NULL REFERENCES sections(id)   ON DELETE CASCADE,
    subject_id    BIGINT      NOT NULL REFERENCES subjects(id)   ON DELETE CASCADE,
    faculty_id    BIGINT      NOT NULL REFERENCES faculty(id)    ON DELETE CASCADE,
    classroom_id  BIGINT      NOT NULL REFERENCES classrooms(id) ON DELETE CASCADE,
    day_of_week   VARCHAR(10) NOT NULL,
    time_slot_id  BIGINT      NOT NULL REFERENCES time_slots(id) ON DELETE CASCADE,
    is_lab        BOOLEAN     NOT NULL DEFAULT FALSE,
    is_locked     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_entry_day CHECK (day_of_week IN ('MON','TUE','WED','THU','FRI','SAT'))
);

-- ── Timetable Conflicts ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS timetable_conflicts (
    id            BIGSERIAL PRIMARY KEY,
    timetable_id  BIGINT      NOT NULL REFERENCES timetables(id) ON DELETE CASCADE,
    conflict_type VARCHAR(50) NOT NULL,  -- FACULTY_DOUBLE_BOOKED, ROOM_DOUBLE_BOOKED, etc.
    description   TEXT        NOT NULL,
    entry_id_1    BIGINT      REFERENCES timetable_entries(id) ON DELETE SET NULL,
    entry_id_2    BIGINT      REFERENCES timetable_entries(id) ON DELETE SET NULL,
    is_resolved   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- ── Audit Log ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGSERIAL PRIMARY KEY,
    entity_name VARCHAR(100) NOT NULL,
    entity_id   BIGINT,
    action      VARCHAR(20)  NOT NULL,  -- CREATE, UPDATE, DELETE
    old_value   JSON,
    new_value   JSON,
    performed_by VARCHAR(100),
    performed_at TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT NOW(),
    ip_address  VARCHAR(45)
);

-- ── Indexes for Timetable Queries ─────────────────────────────
CREATE INDEX IF NOT EXISTS idx_entries_timetable   ON timetable_entries(timetable_id);
CREATE INDEX IF NOT EXISTS idx_entries_faculty_day ON timetable_entries(faculty_id, day_of_week);
CREATE INDEX IF NOT EXISTS idx_entries_room_day    ON timetable_entries(classroom_id, day_of_week);
CREATE INDEX IF NOT EXISTS idx_entries_section_day ON timetable_entries(section_id, day_of_week);
CREATE INDEX IF NOT EXISTS idx_entries_slot        ON timetable_entries(time_slot_id);
CREATE INDEX IF NOT EXISTS idx_availability_faculty ON faculty_availability(faculty_id);
CREATE INDEX IF NOT EXISTS idx_conflicts_timetable  ON timetable_conflicts(timetable_id);
CREATE INDEX IF NOT EXISTS idx_audit_entity         ON audit_log(entity_name, entity_id);
