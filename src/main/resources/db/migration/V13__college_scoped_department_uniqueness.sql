-- V13: Department name uniqueness is now scoped per college (college_id, name).
-- V1 created `name VARCHAR(150) NOT NULL UNIQUE`, a global constraint that
-- prevents two colleges from each having a department called "CSE". Replace it
-- with a composite unique key so department names are unique only within their
-- own college. Existing rows were already globally unique by name and were all
-- backfilled to college_id = 1 by V12, so the composite key remains satisfiable
-- without touching any data.

ALTER TABLE departments DROP CONSTRAINT IF EXISTS departments_name_key;

ALTER TABLE departments ADD CONSTRAINT uk_departments_college_name UNIQUE (college_id, name);