package com.erp.timetable.module.timetable.benchmark;

import com.erp.timetable.module.timetable.api.AbstractTimetableApiE2E;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 9 — end-to-end validation of the scheduling engines against the actual
 * complete ERP dataset ({@code dataset/tt1-dump.sql}, exported from the live
 * system on 2026-08-06).
 *
 * <p>This harness drives the real REST path exactly as the frontend does
 * ({@code POST /timetable/generate}, {@code POST /timetable/{id}/regenerate-unlocked},
 * {@code PATCH /timetable/entries/{id}/lock}) and performs, in order:
 * <ol>
 *   <li><b>Dataset discovery</b> — live counts of every master/timetable table.</li>
 *   <li><b>Integrity checks</b> — FK referential integrity, break-slot usage,
 *       duplicate windows, actual clash recount, curriculum membership, faculty
 *       weekly/daily caps, room capacity, conflict-row consistency.</li>
 *   <li><b>Baseline snapshot</b> — master-data fingerprint taken before any
 *       generation.</li>
 *   <li><b>Per-section generation</b> — one timetable per section of the dataset
 *       ({@code 25, 26, 27, 28}), generated sequentially so every later section
 *       contends with the earlier timetables for the shared faculty and the single
 *       classroom, exactly like the production multi-section flow.</li>
 *   <li><b>Independent clash recount</b> — faculty/room/section window collisions
 *       recounted straight from the DB across ALL timetables (engine-independent).</li>
 *   <li><b>Lock preservation</b> — two entries locked, then
 *       {@code regenerate-unlocked}, verifying the locked placements survive
 *       byte-for-byte and the DB stays clash-free.</li>
 *   <li><b>Completeness</b> — per section, requested == assigned + unassigned.</li>
 *   <li><b>Engine comparison</b> — one engine pinned per concrete subclass.</li>
 *   <li><b>Restoration / no-mutation</b> — the master-data fingerprint must be
 *       unchanged after every generation + lock + regeneration; the dump is
 *       re-loaded by {@link AbstractTimetableApiE2E#loadDataset()} for every
 *       test and the test transaction rolls everything back.</li>
 * </ol>
 *
 * <p>Nothing here modifies production scheduling logic, constraints, scoring
 * weights, the default engine, the frontend, REST contracts or the schema. If
 * the real data exposes a correctness problem the test fails with the evidence;
 * it is reported, never patched.
 */
public abstract class AbstractPhase9RealDataValidation extends AbstractTimetableApiE2E {

    protected static final int MEASURED_RUNS = 3;
    protected static final int N_A = -1;

    private static final String SESSION = "2025-2026 EVEN";

    private static final Pattern SOLVER_SCORE_PATTERN = Pattern.compile(
        "Solver Score\\s*:\\s*(?:(\\d+)hard(?:/([+-]?\\d+)soft)?|([+-]?\\d+)soft|([+-]?\\d+))");

    private static final List<String> MASTER_TABLES = List.of(
        "DEPARTMENTS ORDER BY ID",
        "ACADEMIC_YEARS ORDER BY ID",
        "SECTIONS ORDER BY ID",
        "FACULTY ORDER BY ID",
        "FACULTY_AVAILABILITY ORDER BY ID",
        "SUBJECTS ORDER BY ID",
        "CLASSROOMS ORDER BY ID",
        "TIME_SLOTS ORDER BY ID",
        "USERS ORDER BY ID",
        "ROLES ORDER BY ID",
        "USER_ROLES ORDER BY ROLE_ID, USER_ID");

    @PersistenceContext
    protected EntityManager entityManager;

    /** Number of entries the engine is expected to produce for the first section of the dataset. */
    protected abstract int expectedPrimarySectionAssigned();

    /** Aggregate invariants asserted across all measured runs (engine-specific). */
    protected abstract void assertScenarioInvariants(List<List<SectionResult>> runs);

    @Test
    void phase9RealDataValidation(CapturedOutput output) throws Exception {
        System.out.println("P9_BEGIN|engine=" + engineName());

        long deptId = discoverDepartmentId();
        int semester = discoverSemester();
        List<Long> sections = discoverSectionIds();
        System.out.println("P9_DATASET|engine=" + engineName()
            + "|departmentId=" + deptId + "|semester=" + semester
            + "|sections=" + sections);

        // ── STEP 1: dataset discovery ────────────────────────────────────────
        printDiscovery();

        // ── STEP 2: integrity ────────────────────────────────────────────────
        List<String> violations = runIntegrityChecks(deptId, semester, sections);
        printIntegrity(violations);
        assertTrue(violations.isEmpty(),
            "Phase 9 integrity checks exposed " + violations.size() + " violation(s) in the real dataset: " + violations);

        // ── STEP 3: baseline snapshot (before any generation) ────────────────
        Fingerprint baseline = snapshotMasterFingerprint();
        printBaseline(deptId, semester, sections);

        // ── warm-up pass ─────────────────────────────────────────────────────
        resetTimetableTables();
        printRun("warmup", runSectionsOnce(output, deptId, semester, sections));

        // ── STEP 4/5: measured per-section runs ──────────────────────────────
        List<List<SectionResult>> runs = new ArrayList<>();
        for (int r = 0; r < MEASURED_RUNS; r++) {
            resetTimetableTables();
            List<SectionResult> run = runSectionsOnce(output, deptId, semester, sections);
            runs.add(run);
            printRun("run" + r, run);

            // STEP 8 — completeness per section
            for (SectionResult s : run) {
                assertEquals(s.requested(), s.assigned() + s.unassigned(),
                    "completeness: requested must equal assigned + unassigned (engine=" + engineName()
                        + ", section=" + s.sectionId() + ", run=" + r + ")");
                assertEquals(0, s.facultyClashes() + s.roomClashes() + s.sectionClashes(),
                    "within-timetable clashes on section " + s.sectionId() + " run " + r + " (engine=" + engineName() + ")");
            }

            // STEP 6 — independent cross-timetable clash recount from the DB
            Recount recount = recountFromDb();
            assertEquals(0, recount.facultyClashes() + recount.roomClashes() + recount.sectionClashes(),
                "independent cross-timetable recount must be clash-free (engine=" + engineName() + ", run=" + r + "): " + recount);
        }

        assertScenarioInvariants(runs);

        // ── STEP 7: lock preservation on the last measured run ───────────────
        runLockPreservationCheck(runs.get(runs.size() - 1));

        // ── STEP 10: master-data no-mutation / database restoration ──────────
        Fingerprint after = snapshotMasterFingerprint();
        assertMasterUnchanged(baseline, after);
        System.out.println("P9_RESTORE|engine=" + engineName()
            + "|masterTables=" + baseline.tables().size()
            + "|unchanged=true");

        // ── STEP 9: engine comparison table ──────────────────────────────────
        printSummary(runs);
        System.out.println("P9_END|engine=" + engineName());
    }

    // =====================================================================
    // STEP 1 — dataset discovery
    // =====================================================================

    private void printDiscovery() {
        System.out.println("P9_DISCOVERY|engine=" + engineName()
            + "|departments=" + count("SELECT COUNT(*) FROM DEPARTMENTS")
            + "|academicYears=" + count("SELECT COUNT(*) FROM ACADEMIC_YEARS")
            + "|sections=" + count("SELECT COUNT(*) FROM SECTIONS")
            + "|faculty=" + count("SELECT COUNT(*) FROM FACULTY")
            + "|facultyAvailable=" + count("SELECT COUNT(*) FROM FACULTY WHERE STATUS='AVAILABLE'")
            + "|facultyLeave=" + count("SELECT COUNT(*) FROM FACULTY WHERE STATUS='LEAVE'")
            + "|facultyAvailability=" + count("SELECT COUNT(*) FROM FACULTY_AVAILABILITY")
            + "|subjects=" + count("SELECT COUNT(*) FROM SUBJECTS")
            + "|subjectsTheory=" + count("SELECT COUNT(*) FROM SUBJECTS WHERE SUBJECT_TYPE='THEORY'")
            + "|subjectsLab=" + count("SELECT COUNT(*) FROM SUBJECTS WHERE SUBJECT_TYPE='LAB'")
            + "|subjectsDeptLevel=" + count("SELECT COUNT(*) FROM SUBJECTS WHERE SECTION_ID IS NULL")
            + "|classrooms=" + count("SELECT COUNT(*) FROM CLASSROOMS")
            + "|classroomsLab=" + count("SELECT COUNT(*) FROM CLASSROOMS WHERE ROOM_TYPE='LAB'")
            + "|classroomsLecture=" + count("SELECT COUNT(*) FROM CLASSROOMS WHERE ROOM_TYPE='LECTURE_HALL'")
            + "|classroomsAvailable=" + count("SELECT COUNT(*) FROM CLASSROOMS WHERE STATUS='AVAILABLE'")
            + "|timeSlots=" + count("SELECT COUNT(*) FROM TIME_SLOTS")
            + "|timeSlotsBreak=" + count("SELECT COUNT(*) FROM TIME_SLOTS WHERE IS_BREAK=TRUE")
            + "|timetables=" + count("SELECT COUNT(*) FROM TIMETABLES")
            + "|timetableEntries=" + count("SELECT COUNT(*) FROM TIMETABLE_ENTRIES")
            + "|timetableConflicts=" + count("SELECT COUNT(*) FROM TIMETABLE_CONFLICTS")
            + "|users=" + count("SELECT COUNT(*) FROM USERS")
            + "|roles=" + count("SELECT COUNT(*) FROM ROLES")
            + "|userRoles=" + count("SELECT COUNT(*) FROM USER_ROLES"));
    }

    private void printBaseline(long deptId, int semester, List<Long> sections) {
        for (long sectionId : sections) {
            System.out.println("P9_BASELINE|engine=" + engineName()
                + "|section=" + sectionId
                + "|requested=" + computeRequestedDemand(deptId, sectionId, semester)
                + "|curriculumSubjects=" + curriculumSubjectCount(deptId, sectionId, semester));
        }
        for (long timetableId : jdbcTemplate.queryForList("SELECT ID FROM TIMETABLES ORDER BY ID", Long.class)) {
            System.out.println("P9_BASELINE_TT|engine=" + engineName()
                + "|timetableId=" + timetableId
                + "|entries=" + count("SELECT COUNT(*) FROM TIMETABLE_ENTRIES WHERE TIMETABLE_ID=" + timetableId)
                + "|conflicts=" + count("SELECT COUNT(*) FROM TIMETABLE_CONFLICTS WHERE TIMETABLE_ID=" + timetableId)
                + "|storedConflictCount=" + count("SELECT CONFLICT_COUNT FROM TIMETABLES WHERE ID=" + timetableId)
                + "|storedOptimization=" + count("SELECT OPTIMIZATION_SCORE FROM TIMETABLES WHERE ID=" + timetableId));
        }
    }

    // =====================================================================
    // STEP 2 — integrity checks
    // =====================================================================

    private List<String> runIntegrityChecks(long deptId, int semester, List<Long> sections) {
        List<String> violations = new ArrayList<>();

        // 1. NULL / orphaned FK references in TIMETABLE_ENTRIES
        Integer orphanEntries = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM TIMETABLE_ENTRIES e "
                + "LEFT JOIN TIMETABLES t ON t.ID = e.TIMETABLE_ID "
                + "LEFT JOIN FACULTY f ON f.ID = e.FACULTY_ID "
                + "LEFT JOIN CLASSROOMS c ON c.ID = e.CLASSROOM_ID "
                + "LEFT JOIN SUBJECTS s ON s.ID = e.SUBJECT_ID "
                + "LEFT JOIN SECTIONS sec ON sec.ID = e.SECTION_ID "
                + "LEFT JOIN TIME_SLOTS ts ON ts.ID = e.TIME_SLOT_ID "
                + "WHERE e.TIMETABLE_ID IS NULL OR e.FACULTY_ID IS NULL OR e.CLASSROOM_ID IS NULL "
                + "OR e.SUBJECT_ID IS NULL OR e.SECTION_ID IS NULL OR e.TIME_SLOT_ID IS NULL "
                + "OR t.ID IS NULL OR f.ID IS NULL OR c.ID IS NULL OR s.ID IS NULL OR sec.ID IS NULL OR ts.ID IS NULL",
            Integer.class);
        if (orphanEntries != null && orphanEntries > 0) {
            violations.add("TIMETABLE_ENTRIES: " + orphanEntries + " row(s) with NULL or orphaned FK references");
        }

        // 2. entries scheduled on a break time slot
        Integer onBreak = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM TIMETABLE_ENTRIES e JOIN TIME_SLOTS ts ON ts.ID = e.TIME_SLOT_ID WHERE ts.IS_BREAK = TRUE",
            Integer.class);
        if (onBreak != null && onBreak > 0) {
            violations.add("TIMETABLE_ENTRIES: " + onBreak + " row(s) scheduled on a break time slot");
        }

        // 3. duplicate (timetable, day, slot) windows
        Integer dupWindows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM (SELECT TIMETABLE_ID, DAY_OF_WEEK, TIME_SLOT_ID FROM TIMETABLE_ENTRIES "
                + "GROUP BY TIMETABLE_ID, DAY_OF_WEEK, TIME_SLOT_ID HAVING COUNT(*) > 1)",
            Integer.class);
        if (dupWindows != null && dupWindows > 0) {
            violations.add("TIMETABLE_ENTRIES: " + dupWindows + " duplicate (timetable, day, slot) window(s)");
        }

        // 4. actual faculty/room/section double-bookings anywhere in the dataset
        Recount recount = recountFromDb();
        if (recount.facultyClashes() > 0) {
            violations.add("TIMETABLE_ENTRIES: " + recount.facultyClashes() + " faculty double-booking(s) across all timetables");
        }
        if (recount.roomClashes() > 0) {
            violations.add("TIMETABLE_ENTRIES: " + recount.roomClashes() + " room double-booking(s) across all timetables");
        }
        if (recount.sectionClashes() > 0) {
            violations.add("TIMETABLE_ENTRIES: " + recount.sectionClashes() + " section double-booking(s) across all timetables");
        }

        // 5. every entry's subject must belong to the section's curriculum
        Integer outOfCurriculum = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM TIMETABLE_ENTRIES e "
                + "JOIN SUBJECTS s ON s.ID = e.SUBJECT_ID "
                + "JOIN TIMETABLES t ON t.ID = e.TIMETABLE_ID "
                + "WHERE NOT (s.IS_ACTIVE = TRUE AND s.SEMESTER = t.SEMESTER "
                + "            AND s.DEPARTMENT_ID = t.DEPARTMENT_ID "
                + "            AND (s.SECTION_ID IS NULL OR s.SECTION_ID = e.SECTION_ID))",
            Integer.class);
        if (outOfCurriculum != null && outOfCurriculum > 0) {
            violations.add("TIMETABLE_ENTRIES: " + outOfCurriculum + " row(s) reference a subject outside the section's curriculum");
        }

        // 6. faculty status unknown to the scheduler
        Integer unknownStatus = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM FACULTY WHERE STATUS NOT IN ('AVAILABLE', 'LEAVE')", Integer.class);
        if (unknownStatus != null && unknownStatus > 0) {
            violations.add("FACULTY: " + unknownStatus + " row(s) with a status the scheduler does not handle");
        }

        // 7. faculty weekly load above the configured cap
        Integer weeklyOverload = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM (SELECT f.ID, COUNT(e.ID) AS LOAD, f.MAX_WEEKLY_HOURS FROM FACULTY f "
                + "LEFT JOIN TIMETABLE_ENTRIES e ON e.FACULTY_ID = f.ID "
                + "GROUP BY f.ID, f.MAX_WEEKLY_HOURS HAVING COUNT(e.ID) > f.MAX_WEEKLY_HOURS)",
            Integer.class);
        if (weeklyOverload != null && weeklyOverload > 0) {
            violations.add("FACULTY: " + weeklyOverload + " faculty member(s) over their weekly hour cap");
        }

        // 8. faculty daily load above the configured cap
        Integer dailyOverload = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM (SELECT f.ID, f.MAX_DAILY_HOURS, e.DAY_OF_WEEK, COUNT(e.ID) AS LOAD FROM FACULTY f "
                + "LEFT JOIN TIMETABLE_ENTRIES e ON e.FACULTY_ID = f.ID "
                + "GROUP BY f.ID, f.MAX_DAILY_HOURS, e.DAY_OF_WEEK HAVING COUNT(e.ID) > f.MAX_DAILY_HOURS)",
            Integer.class);
        if (dailyOverload != null && dailyOverload > 0) {
            violations.add("FACULTY: " + dailyOverload + " faculty-day(s) over the daily hour cap");
        }

        // 9. classroom capacity below section strength
        Integer capacityViolation = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM TIMETABLE_ENTRIES e "
                + "JOIN CLASSROOMS c ON c.ID = e.CLASSROOM_ID "
                + "JOIN SECTIONS sec ON sec.ID = e.SECTION_ID "
                + "WHERE sec.STUDENT_STRENGTH IS NOT NULL AND c.CAPACITY < sec.STUDENT_STRENGTH",
            Integer.class);
        if (capacityViolation != null && capacityViolation > 0) {
            violations.add("TIMETABLE_ENTRIES: " + capacityViolation + " row(s) in a classroom smaller than the section");
        }

        // 10. conflict rows referencing a missing timetable
        Integer orphanConflicts = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM TIMETABLE_CONFLICTS c LEFT JOIN TIMETABLES t ON t.ID = c.TIMETABLE_ID "
                + "WHERE c.TIMETABLE_ID IS NULL OR t.ID IS NULL", Integer.class);
        if (orphanConflicts != null && orphanConflicts > 0) {
            violations.add("TIMETABLE_CONFLICTS: " + orphanConflicts + " row(s) referencing a missing timetable");
        }

        // 11. stored conflict_count must equal the number of conflict rows
        List<Map<String, Object>> countMismatch = jdbcTemplate.queryForList(
            "SELECT t.ID AS TT_ID, t.CONFLICT_COUNT AS STORED, (SELECT COUNT(*) FROM TIMETABLE_CONFLICTS c WHERE c.TIMETABLE_ID = t.ID) AS ACTUAL "
                + "FROM TIMETABLES t WHERE t.CONFLICT_COUNT <> "
                + "(SELECT COUNT(*) FROM TIMETABLE_CONFLICTS c WHERE c.TIMETABLE_ID = t.ID)");
        for (Map<String, Object> row : countMismatch) {
            violations.add("TIMETABLES: id " + row.get("TT_ID") + " stores conflict_count=" + row.get("STORED")
                + " but has " + row.get("ACTUAL") + " conflict row(s)");
        }

        // 12. no disabled academic year referenced by a timetable's section
        Integer disabledYear = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM TIMETABLES t JOIN SECTIONS sec ON sec.ID = t.SECTION_ID "
                + "JOIN ACADEMIC_YEARS y ON y.ID = sec.ACADEMIC_YEAR_ID WHERE y.IS_ENABLED = FALSE", Integer.class);
        if (disabledYear != null && disabledYear > 0) {
            violations.add("TIMETABLES: " + disabledYear + " timetable(s) for a disabled academic year");
        }

        return violations;
    }

    private void printIntegrity(List<String> violations) {
        System.out.println("P9_INTEGRITY|engine=" + engineName() + "|violations=" + violations.size());
        for (String v : violations) {
            System.out.println("P9_INTEGRITY_VIOLATION|engine=" + engineName() + "|" + v);
        }
    }

    // =====================================================================
    // STEP 4/5 — per-section generation
    // =====================================================================

    private List<SectionResult> runSectionsOnce(CapturedOutput output, long deptId, int semester, List<Long> sections)
            throws Exception {
        List<SectionResult> results = new ArrayList<>();
        for (long sectionId : sections) {
            String before = output.getAll();
            long start = System.nanoTime();
            JsonNode data = postGenerateExpectSuccess(deptId, sectionId, semester, SESSION);
            long apiMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            String logs = logsSince(output, before);

            int requested = computeRequestedDemand(deptId, sectionId, semester);
            int assigned = data.get("entries").size();
            int unassigned = requested - assigned;

            int[] hs = parseHardSoft(logs);
            int hard = hs != null ? hs[0] : N_A;
            int soft = hs != null ? hs[1] : N_A;

            int conflicts = data.get("conflictCount").asInt();
            int solverInfeasible = countSolverInfeasible(data);

            ClashCounts clashes = countClashes(data.get("entries"));

            long[] timings = parseEngineTimings(logs);
            long engineMs = timings[0];
            long persistMs = engineMs > 0 ? Math.max(0L, apiMs - engineMs) : N_A;

            long timetableId = data.get("id").asLong();
            results.add(new SectionResult(sectionId, timetableId, requested, assigned, unassigned, hard, soft,
                conflicts, solverInfeasible, clashes.faculty(), clashes.room(), clashes.section(),
                apiMs, timings[1], timings[2], timings[3], persistMs,
                countOtherTimetableEntries(timetableId), data.get("optimizationScore").asInt()));
        }
        return results;
    }

    // =====================================================================
    // STEP 6 — independent clash recount
    // =====================================================================

    /** Recounts every (faculty|room|section, day, slot) collision across ALL entries of ALL timetables straight from the DB. */
    private Recount recountFromDb() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT FACULTY_ID, CLASSROOM_ID, SECTION_ID, DAY_OF_WEEK, TIME_SLOT_ID FROM TIMETABLE_ENTRIES");
        Set<String> facultyWindows = new HashSet<>();
        Set<String> roomWindows = new HashSet<>();
        Set<String> sectionWindows = new HashSet<>();
        int facultyClashes = 0;
        int roomClashes = 0;
        int sectionClashes = 0;
        for (Map<String, Object> row : rows) {
            String day = String.valueOf(row.get("DAY_OF_WEEK"));
            String slot = String.valueOf(row.get("TIME_SLOT_ID"));
            String sec = String.valueOf(row.get("SECTION_ID"));
            String fac = String.valueOf(row.get("FACULTY_ID"));
            String room = String.valueOf(row.get("CLASSROOM_ID"));
            if (!facultyWindows.add(fac + "|" + day + "|" + slot)) {
                facultyClashes++;
            }
            if (!roomWindows.add(room + "|" + day + "|" + slot)) {
                roomClashes++;
            }
            if (!sectionWindows.add(sec + "|" + day + "|" + slot)) {
                sectionClashes++;
            }
        }
        return new Recount(facultyClashes, roomClashes, sectionClashes, rows.size());
    }

    // =====================================================================
    // STEP 7 — lock preservation
    // =====================================================================

    private void runLockPreservationCheck(List<SectionResult> run) throws Exception {
        SectionResult primary = run.get(0);
        long timetableId = primary.timetableId();
        JsonNode before = getTimetable(timetableId);
        JsonNode entries = before.get("entries");
        assertTrue(entries.size() > 2, "expected at least 2 entries to lock on timetable " + timetableId);

        long e1 = entries.get(0).get("id").asLong();
        long e2 = entries.get(1).get("id").asLong();
        Map<Long, String[]> lockedBefore = new HashMap<>();
        lockedBefore.put(e1, entryFingerprint(entries.get(0)));
        lockedBefore.put(e2, entryFingerprint(entries.get(1)));

        lockEntry(e1);
        lockEntry(e2);

        JsonNode after = regenerateUnlocked(timetableId);
        int lockedFound = 0;
        for (JsonNode e : after.get("entries")) {
            long id = e.get("id").asLong();
            if (lockedBefore.containsKey(id)) {
                assertTrue(Arrays.equals(lockedBefore.get(id), entryFingerprint(e)),
                    "locked entry " + id + " changed placement across regenerate-unlocked (engine=" + engineName() + ")");
                assertTrue(e.get("isLocked").asBoolean(), "locked entry " + id + " must remain locked");
                lockedFound++;
            }
        }
        assertEquals(2, lockedFound, "both locked entries must survive regenerate-unlocked (engine=" + engineName() + ")");

        Recount recount = recountFromDb();
        assertEquals(0, recount.facultyClashes() + recount.roomClashes() + recount.sectionClashes(),
            "regenerate-unlocked must leave the database clash-free (engine=" + engineName() + "): " + recount);

        System.out.println("P9_LOCK|engine=" + engineName()
            + "|timetableId=" + timetableId
            + "|lockedPreserved=" + lockedFound
            + "|entriesAfter=" + after.get("entries").size()
            + "|facultyClashes=" + recount.facultyClashes()
            + "|roomClashes=" + recount.roomClashes()
            + "|sectionClashes=" + recount.sectionClashes());
    }

    private String[] entryFingerprint(JsonNode e) {
        return new String[]{
            e.get("dayOfWeek").asText(),
            e.get("timeSlotId").asText(),
            e.get("facultyId").asText(),
            e.get("classroomId").asText(),
            e.get("subjectId").asText()};
    }

    // =====================================================================
    // STEP 10 — master-data no-mutation / restoration
    // =====================================================================

    private Fingerprint snapshotMasterFingerprint() {
        Map<String, TableFingerprint> tables = new LinkedHashMap<>();
        for (String spec : MASTER_TABLES) {
            String[] parts = spec.split(" ORDER BY ");
            String table = parts[0].trim();
            String orderBy = parts[1];
            long count = Optional.ofNullable(
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).orElse(0L);
            tables.put(table, new TableFingerprint(count, hashRows(table, orderBy)));
        }
        return new Fingerprint(tables);
    }

    private String hashRows(String table, String orderBy) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            jdbcTemplate.query("SELECT * FROM " + table + " ORDER BY " + orderBy, rs -> {
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i <= columns; i++) {
                        if (i > 1) {
                            sb.append('|');
                        }
                        sb.append(rs.getObject(i));
                    }
                    md.update(sb.toString().getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0x1E);
                }
                return null;
            });
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertMasterUnchanged(Fingerprint before, Fingerprint after) {
        assertEquals(before.tables().keySet(), after.tables().keySet(), "master table set changed");
        for (String table : before.tables().keySet()) {
            TableFingerprint b = before.tables().get(table);
            TableFingerprint a = after.tables().get(table);
            assertEquals(b.count(), a.count(), "master table " + table + " row count changed");
            assertEquals(b.hash(), a.hash(), "master table " + table + " content changed");
        }
    }

    // =====================================================================
    // Dataset helpers
    // =====================================================================

    private long discoverDepartmentId() {
        List<Long> ids = jdbcTemplate.queryForList("SELECT ID FROM DEPARTMENTS ORDER BY ID", Long.class);
        assertTrue(!ids.isEmpty(), "dataset must contain at least one department");
        return ids.get(0);
    }

    private int discoverSemester() {
        Integer min = jdbcTemplate.queryForObject("SELECT MIN(SEMESTER) FROM SUBJECTS", Integer.class);
        assertEquals(1, min, "the real dataset's subjects are all semester 1");
        return min;
    }

    private List<Long> discoverSectionIds() {
        return jdbcTemplate.queryForList("SELECT ID FROM SECTIONS ORDER BY ID", Long.class);
    }

    /**
     * Mirrors the production demand calculation ({@code CurriculumDataLoader}
     * section-aware union + {@code SubjectDemandService#calculateWeeklyHours}):
     * every subject contributes {@code theoryHours + practicalHours}, independent
     * of its type — practical hours need a LAB room, theory hours a LECTURE_HALL.
     */
    private int computeRequestedDemand(long deptId, long sectionId, int semester) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT THEORY_HOURS, PRACTICAL_HOURS FROM SUBJECTS "
                + "WHERE DEPARTMENT_ID = ? AND SEMESTER = ? AND IS_ACTIVE = TRUE "
                + "AND (SECTION_ID = ? OR SECTION_ID IS NULL)", deptId, semester, sectionId);
        int demand = 0;
        for (Map<String, Object> row : rows) {
            Object theory = row.get("THEORY_HOURS");
            Object practical = row.get("PRACTICAL_HOURS");
            int theoryHours = theory == null ? 0 : ((Number) theory).intValue();
            int practicalHours = practical == null ? 0 : ((Number) practical).intValue();
            demand += Math.max(theoryHours, 0) + Math.max(practicalHours, 0);
        }
        return demand;
    }

    private int curriculumSubjectCount(long deptId, long sectionId, int semester) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM SUBJECTS WHERE DEPARTMENT_ID = ? AND SEMESTER = ? AND IS_ACTIVE = TRUE "
                + "AND (SECTION_ID = ? OR SECTION_ID IS NULL)", Integer.class, deptId, semester, sectionId);
        return n == null ? 0 : n;
    }

    private long count(String sql) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }

    // =====================================================================
    // Engine-log / helper methods (mirror AbstractPhase8Benchmark)
    // =====================================================================

    protected void resetTimetableTables() {
        entityManager.clear();
        jdbcTemplate.execute("DELETE FROM TIMETABLE_CONFLICTS");
        jdbcTemplate.execute("DELETE FROM TIMETABLE_ENTRIES");
        jdbcTemplate.execute("DELETE FROM TIMETABLES");
    }

    protected String logsSince(CapturedOutput output, String before) {
        String all = output.getAll();
        return all.length() >= before.length() ? all.substring(before.length()) : "";
    }

    protected long[] parseEngineTimings(String logs) {
        return new long[]{
            parseMillis(logs, "Engine total"),
            parseMillis(logs, "Solved in"),
            parseMillis(logs, "Prepared planning model in"),
            parseMillis(logs, "Applied solver result in")};
    }

    protected int[] parseHardSoft(String logs) {
        Matcher m = SOLVER_SCORE_PATTERN.matcher(logs);
        if (m.find()) {
            if (m.group(1) != null) {
                return new int[]{Integer.parseInt(m.group(1)),
                    m.group(2) != null ? Integer.parseInt(m.group(2)) : 0};
            }
            if (m.group(3) != null) {
                return new int[]{0, Integer.parseInt(m.group(3))};
            }
            return new int[]{Integer.parseInt(m.group(4)), 0};
        }
        return null;
    }

    protected int countSolverInfeasible(JsonNode data) {
        int n = 0;
        for (JsonNode c : data.get("conflicts")) {
            if ("SOLVER_INFEASIBLE".equals(c.get("conflictType").asText())) {
                n++;
            }
        }
        return n;
    }

    protected ClashCounts countClashes(JsonNode entries) {
        Set<String> windows = new HashSet<>();
        Set<String> faculty = new HashSet<>();
        Set<String> rooms = new HashSet<>();
        int facultyClashes = 0;
        int roomClashes = 0;
        int sectionClashes = 0;
        for (JsonNode e : entries) {
            String window = e.get("dayOfWeek").asText() + "|" + e.get("timeSlotId").asText();
            if (!windows.add(window)) {
                sectionClashes++;
            }
            if (!faculty.add(e.get("facultyId").asText() + "|" + window)) {
                facultyClashes++;
            }
            if (!rooms.add(e.get("classroomId").asText() + "|" + window)) {
                roomClashes++;
            }
        }
        return new ClashCounts(facultyClashes, roomClashes, sectionClashes);
    }

    protected int countOtherTimetableEntries(long timetableId) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM TIMETABLE_ENTRIES WHERE TIMETABLE_ID <> ?", Integer.class, timetableId);
        return n == null ? 0 : n;
    }

    protected JsonNode lockEntry(long entryId) throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/timetable/entries/" + entryId + "/lock")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
        var root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(root.get("success").asBoolean(), "lock toggle must succeed: " + root);
        return root.get("data");
    }

    protected JsonNode getTimetable(long timetableId) throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/timetable/" + timetableId))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    protected JsonNode regenerateUnlocked(long timetableId) throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/timetable/" + timetableId + "/regenerate-unlocked")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
        var root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(root.get("success").asBoolean(), "regenerate-unlocked must succeed: " + root);
        return root.get("data");
    }

    // =====================================================================
    // Printing
    // =====================================================================

    private void printRun(String label, List<SectionResult> run) {
        for (SectionResult s : run) {
            System.out.println("P9_RUN|engine=" + engineName() + "|label=" + label
                + "|section=" + s.sectionId()
                + "|requested=" + s.requested()
                + "|assigned=" + s.assigned()
                + "|unassigned=" + s.unassigned()
                + "|hard=" + (s.hard() == N_A ? "-" : s.hard())
                + "|soft=" + (s.soft() == N_A ? "-" : s.soft())
                + "|conflicts=" + s.conflicts()
                + "|solverInfeasible=" + s.solverInfeasible()
                + "|facultyClashes=" + s.facultyClashes()
                + "|roomClashes=" + s.roomClashes()
                + "|sectionClashes=" + s.sectionClashes()
                + "|occupancyFacts=" + s.occupancyFacts()
                + "|optimization=" + s.optimization()
                + "|apiMs=" + s.apiMs()
                + "|solverMs=" + (s.solverMs() > 0 ? s.solverMs() : "-")
                + "|mappingMs=" + (s.mappingMs() > 0 ? s.mappingMs() : "-")
                + "|applyMs=" + (s.applyMs() > 0 ? s.applyMs() : "-")
                + "|persistMs=" + (s.persistMs() >= 0 ? s.persistMs() : "-"));
        }
        Recount recount = recountFromDb();
        System.out.println("P9_RECCOUNT|engine=" + engineName() + "|label=" + label
            + "|entries=" + recount.entries()
            + "|facultyClashes=" + recount.facultyClashes()
            + "|roomClashes=" + recount.roomClashes()
            + "|sectionClashes=" + recount.sectionClashes());
    }

    private void printSummary(List<List<SectionResult>> runs) {
        List<SectionResult> all = runs.stream().flatMap(List::stream).toList();
        List<Long> sections = all.stream().map(SectionResult::sectionId).distinct().toList();
        for (long sectionId : sections) {
            List<SectionResult> per = all.stream().filter(s -> s.sectionId() == sectionId).toList();
            long[] api = per.stream().mapToLong(SectionResult::apiMs).toArray();
            long[] solver = per.stream().mapToLong(SectionResult::solverMs).filter(v -> v > 0).toArray();
            int[] assigned = per.stream().mapToInt(SectionResult::assigned).toArray();
            int[] conflicts = per.stream().mapToInt(SectionResult::conflicts).toArray();
            int[] occupancy = per.stream().mapToInt(SectionResult::occupancyFacts).toArray();
            int[] unassigned = per.stream().mapToInt(SectionResult::unassigned).toArray();
            int[] hard = per.stream().mapToInt(SectionResult::hard).filter(v -> v != N_A).toArray();
            System.out.println("P9_SUMMARY|engine=" + engineName() + "|section=" + sectionId
                + "|runs=" + per.size()
                + "|assigned(min=" + min(assigned) + ",max=" + max(assigned) + ",avg=" + avg(assigned) + ")"
                + "|unassigned(min=" + min(unassigned) + ",max=" + max(unassigned) + ")"
                + "|hard(" + (hard.length == 0 ? "-" : "min=" + min(hard) + ",max=" + max(hard)) + ")"
                + "|apiMs(min=" + min(api) + ",max=" + max(api) + ",avg=" + avg(api) + ")"
                + "|solverMs(" + (solver.length == 0 ? "-" : "min=" + min(solver) + ",max=" + max(solver) + ",avg=" + avg(solver)) + ")"
                + "|conflicts(min=" + min(conflicts) + ",max=" + max(conflicts) + ")"
                + "|occupancyFacts(min=" + min(occupancy) + ",max=" + max(occupancy) + ")");
        }
    }

    private static long min(long[] a) {
        return Arrays.stream(a).min().orElseThrow();
    }

    private static long max(long[] a) {
        return Arrays.stream(a).max().orElseThrow();
    }

    private static double avg(long[] a) {
        return Arrays.stream(a).average().orElseThrow();
    }

    private static int min(int[] a) {
        return Arrays.stream(a).min().orElseThrow();
    }

    private static int max(int[] a) {
        return Arrays.stream(a).max().orElseThrow();
    }

    private static double avg(int[] a) {
        return Arrays.stream(a).average().orElseThrow();
    }

    // =====================================================================
    // Records
    // =====================================================================

    protected record SectionResult(long sectionId, long timetableId, int requested, int assigned, int unassigned,
            int hard, int soft, int conflicts, int solverInfeasible, int facultyClashes, int roomClashes,
            int sectionClashes, long apiMs, long solverMs, long mappingMs, long applyMs, long persistMs,
            int occupancyFacts, int optimization) {
    }

    protected record ClashCounts(int faculty, int room, int section) {
    }

    protected record Recount(int facultyClashes, int roomClashes, int sectionClashes, int entries) {
    }

    protected record TableFingerprint(long count, String hash) {
    }

    protected record Fingerprint(Map<String, TableFingerprint> tables) {
    }
}
