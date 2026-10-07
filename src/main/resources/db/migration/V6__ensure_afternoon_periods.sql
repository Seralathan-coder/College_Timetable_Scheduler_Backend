-- ============================================================
-- V6: Ensure a full 7-period day exists (4 periods before lunch,
--     LUNCH break, 3 periods after lunch).
--
-- SAFE / ADDITIVE ONLY:
--   - Uses ON CONFLICT (slot_label) DO NOTHING, so it will never
--     touch, rename, or duplicate a time slot you already have.
--   - Does not delete or modify any row in ANY table.
--   - Does not touch departments, faculty, subjects, classrooms,
--     timetables, or timetable_entries in any way.
-- ============================================================

INSERT INTO time_slots (slot_label, start_time, end_time, is_break, slot_order) VALUES
  ('P1',    '09:00', '09:50', FALSE, 1),
  ('P2',    '09:50', '10:40', FALSE, 2),
  ('P3',    '10:40', '11:30', FALSE, 3),
  ('P4',    '11:30', '12:20', FALSE, 4),
  ('LUNCH', '12:20', '13:10', TRUE,  5),
  ('P5',    '13:10', '14:00', FALSE, 6),
  ('P6',    '14:00', '14:50', FALSE, 7),
  ('P7',    '14:50', '15:40', FALSE, 8)
ON CONFLICT (slot_label) DO NOTHING;

-- If your database still has the old 8th teaching period ('P8') from an
-- earlier seed, it is left exactly as-is by this migration — nothing here
-- removes it. Delete it yourself from the Time Slots / Availability screen
-- if you want exactly 7 teaching periods (P1-P7) instead of 8.
