-- Normalize the time_slots master to exactly 7 teaching periods per day.
--
-- Weekly capacity is computed at runtime as (working days MON-SAT, 6) x
-- (non-break time_slots). An 8th teaching period makes a freshly migrated
-- database offer 48 slots instead of the intended 42. Earlier seeds inserted
-- such an extra slot (the 'P8' row in V3), while the live catalog already
-- exposes only 7 teaching periods (P1-P4, LUNCH, P5-P7) plus one break.
--
-- SAFE / NON-DESTRUCTIVE:
--   - The 7 teaching slots with the LOWEST slot_order are kept unchanged.
--   - Any additional teaching slot (8th onward by slot_order) is DELETED when
--     nothing references it, and otherwise RETAINED but turned into a break so
--     it stops counting towards teaching capacity. That keeps every foreign
--     key intact (time_slot_id is used by timetable_entries,
--     faculty_availability and timetable_configurations.lunch_slot_id).
--   - No department / faculty / subject / classroom / timetable row is touched.
--   - No-op on a catalog that already holds 7 or fewer teaching periods.

DELETE FROM time_slots ts
WHERE ts.is_break = FALSE
  AND ts.id IN (
      SELECT id FROM (
          SELECT id, ROW_NUMBER() OVER (ORDER BY slot_order, id) AS rn
          FROM time_slots
          WHERE is_break = FALSE
      ) ranked
      WHERE ranked.rn > 7
  )
  AND NOT EXISTS (SELECT 1 FROM timetable_entries e WHERE e.time_slot_id = ts.id)
  AND NOT EXISTS (SELECT 1 FROM faculty_availability fa WHERE fa.time_slot_id = ts.id)
  AND NOT EXISTS (SELECT 1 FROM timetable_configurations tc WHERE tc.lunch_slot_id = ts.id);

UPDATE time_slots ts
SET is_break = TRUE
WHERE ts.is_break = FALSE
  AND ts.id IN (
      SELECT id FROM (
          SELECT id, ROW_NUMBER() OVER (ORDER BY slot_order, id) AS rn
          FROM time_slots
          WHERE is_break = FALSE
      ) ranked
      WHERE ranked.rn > 7
  );
