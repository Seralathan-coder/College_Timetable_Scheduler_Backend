-- V14: Login identifiers are now unique PER COLLEGE (college_id, username) and
-- (college_id, email).
-- V1 created `username VARCHAR(100) NOT NULL UNIQUE` and
-- `email VARCHAR(255) NOT NULL UNIQUE` — global constraints that prevent two
-- colleges from each having a HOD / admin with the same Login ID (e.g. both
-- wanting "CSDTamil"). Replace them with composite unique keys so accounts only
-- conflict within their own college. The DB-multiplicity is safe: every legacy
-- row was already unique by username/email and was backfilled to college_id = 1
-- by V12, so the composite keys remain satisfiable without touching any data.

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_username_key;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;

ALTER TABLE users ADD CONSTRAINT uk_users_college_username UNIQUE (college_id, username);
ALTER TABLE users ADD CONSTRAINT uk_users_college_email UNIQUE (college_id, email);