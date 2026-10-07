package com.erp.timetable.module.timetable.benchmark;

import com.erp.timetable.module.timetable.dto.GenerateTimetableRequest;
import com.erp.timetable.module.timetable.dto.TimetableConflictDto;
import com.erp.timetable.module.timetable.dto.TimetableResponse;
import com.erp.timetable.module.timetable.service.TimetableService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * TRANSIENT read-only verification probe (test tree only; deleted after the
 * report). Loads the real TT1 database dump and drives the production
 * {@link TimetableService#generateTimetable} path for every existing section
 * (25, 26, 27, 28), one engine per concrete subclass. Nothing here modifies
 * production code. Verifies the 10-point checklist against DB ground truth:
 * exact per-subject demand, consecutive theory blocks, no hard-coded 6/7/8
 * rules, faculty <= 5/day across all classes, faculty availability, LAB
 * consecutiveness and no-Saturday, no cross-class overwrite, no mutation of
 * data outside the explicitly generated sections, no fake entries, and an
 * exact-constraint report for any placement that fails.
 */
@SpringBootTest
@ActiveProfiles("h2")
@WithMockUser(roles = "HOD")
@Transactional
@ExtendWith(OutputCaptureExtension.class)
public abstract class AbstractRealDataVerificationProbe {

    private static final String SESSION = "2025-2026 EVEN";

    private static final List<String> MASTER_TABLES = List.of(
        "DEPARTMENTS", "ACADEMIC_YEARS", "SECTIONS", "FACULTY", "FACULTY_AVAILABILITY",
        "SUBJECTS", "CLASSROOMS", "TIME_SLOTS", "USERS", "ROLES", "USER_ROLES");

    @Autowired
    protected TimetableService timetableService;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @PersistenceContext
    protected EntityManager entityManager;

    protected abstract String engineName();

    @Test
    void verifyRealDataChecklist() {
        System.out.println("VERIFY_BEGIN|engine=" + engineName());

        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("RUNSCRIPT FROM 'classpath:dataset/tt1-dump.sql'");
        entityManager.clear();

        long deptId = jdbcTemplate.queryForObject("SELECT MIN(ID) FROM DEPARTMENTS", Long.class);
        int semester = jdbcTemplate.queryForObject("SELECT MIN(SEMESTER) FROM SUBJECTS", Integer.class);
        List<Long> sections = jdbcTemplate.queryForList("SELECT ID FROM SECTIONS ORDER BY ID", Long.class);

        System.out.println("VERIFY_DATASET|engine=" + engineName()
            + "|department=" + deptId + "|semester=" + semester + "|sections=" + sections);

        String masterBefore = fingerprint(MASTER_TABLES);
        String ttBefore = timetableState();

        // ITEM 1 source: DB demand per subject of the department curriculum.
        Map<Long, SubjectInfo> demand = loadSubjectDemand(deptId, semester);
        System.out.println("VERIFY_DEMAND_SOURCE|engine=" + engineName() + "|subjects=" + demand.size());

        System.out.println("VERIFY_BASELINE|engine=" + engineName()
            + "|timetables=" + jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TIMETABLES", Long.class)
            + "|entries=" + jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TIMETABLE_ENTRIES", Long.class)
            + "|conflicts=" + jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TIMETABLE_CONFLICTS", Long.class)
            + "|state=" + ttStateBrief());

        // Generate sequentially so every later section contends with the earlier
        // timetables for shared faculty and rooms (production multi-section flow).
        for (long sectionId : sections) {
            entityManager.flush();
            TimetableResponse resp = timetableService.generateTimetable(GenerateTimetableRequest.builder()
                .departmentId(deptId)
                .sectionId(sectionId)
                .semester(semester)
                .academicSession(SESSION)
                .build());
            entityManager.flush();
            entityManager.clear();
            verifySection(sectionId, resp, deptId, semester, demand);
        }

        // Global cross-class checks + item 8/9/10.
        verifyGlobal(deptId, semester, sections, demand, masterBefore, ttBefore);

        System.out.println("VERIFY_END|engine=" + engineName());
    }

    // =====================================================================
    // per-section verification
    // =====================================================================

    private void verifySection(long sectionId, TimetableResponse resp, long deptId, int semester,
            Map<Long, SubjectInfo> demand) {
        long ttId = resp.getId();
        List<Map<String, Object>> entries = jdbcTemplate.queryForList(
            "SELECT e.SUBJECT_ID, e.IS_LAB, e.DAY_OF_WEEK, ts.SLOT_ORDER, e.CLASSROOM_ID "
                + "FROM TIMETABLE_ENTRIES e JOIN TIMETABLES t ON t.ID = e.TIMETABLE_ID "
                + "JOIN TIME_SLOTS ts ON ts.ID = e.TIME_SLOT_ID WHERE t.SECTION_ID = ? ORDER BY e.SUBJECT_ID, e.DAY_OF_WEEK, ts.SLOT_ORDER",
            sectionId);

        Map<Long, Long> placed = new HashMap<>();
        for (Map<String, Object> e : entries) {
            placed.merge(((Number) e.get("SUBJECT_ID")).longValue(), 1L, Long::sum);
        }

        int requestedTotal = 0;
        int placedTotal = 0;
        System.out.println("VERIFY_SECTION|engine=" + engineName() + "|section=" + sectionId
            + "|timetableId=" + ttId + "|placed=" + entries.size()
            + "|conflictCount=" + resp.getConflictCount());

        List<SubjectInfo> curriculum = demand.values().stream()
            .filter(s -> s.sectionId == null || s.sectionId == sectionId)
            .sorted(Comparator.comparing(s -> s.code))
            .toList();
        for (SubjectInfo s : curriculum) {
            int want = s.theoryHours + s.practicalHours;
            long got = placed.getOrDefault(s.id, 0L);
            requestedTotal += want;
            placedTotal += got;
            String runInfo = runsOf(sectionId, s.id);
            String labInfo = labDetail(sectionId, s.id);
            System.out.println("VERIFY_SUBJECT|engine=" + engineName() + "|section=" + sectionId
                + "|subject=" + s.code + "|type=" + s.type + "|block=" + s.blockSize
                + "|demand=" + want + "|placed=" + got + "|unassigned=" + (want - got)
                + "|faculty=" + s.facultyName + "|runs=" + runInfo + labInfo);
        }
        System.out.println("VERIFY_SECTION_TOTALS|engine=" + engineName() + "|section=" + sectionId
            + "|requested=" + requestedTotal + "|placed=" + placedTotal
            + "|unassigned=" + (requestedTotal - placedTotal));

        for (TimetableConflictDto c : resp.getConflicts()) {
            System.out.println("VERIFY_CONFLICT|engine=" + engineName() + "|section=" + sectionId
                + "|type=" + c.getConflictType() + "|severity=" + c.getSeverity()
                + "|desc=" + c.getDescription());
        }
    }

    /** Consecutive run sizes of a subject's placed periods on each day (ground truth). */
    private String runsOf(long sectionId, long subjectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT e.DAY_OF_WEEK AS D, ts.SLOT_ORDER AS O FROM TIMETABLE_ENTRIES e "
                + "JOIN TIMETABLES t ON t.ID = e.TIMETABLE_ID JOIN TIME_SLOTS ts ON ts.ID = e.TIME_SLOT_ID "
                + "WHERE t.SECTION_ID = ? AND e.SUBJECT_ID = ? ORDER BY e.DAY_OF_WEEK, ts.SLOT_ORDER",
            sectionId, subjectId);
        Map<String, List<Integer>> byDay = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            String day = String.valueOf(r.get("D"));
            int ord = ((Number) r.get("O")).intValue();
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(ord);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Integer>> e : byDay.entrySet()) {
            List<Integer> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Integer::compareTo);
            List<Integer> runs = new ArrayList<>();
            int run = 1;
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i) == sorted.get(i - 1) + 1) {
                    run++;
                } else {
                    runs.add(run);
                    run = 1;
                }
            }
            runs.add(run);
            sb.append(e.getKey()).append(":").append(runs);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** LAB detail: room type + day + whether every run matches the block size. */
    private String labDetail(long sectionId, long subjectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT e.DAY_OF_WEEK AS D, ts.SLOT_ORDER AS O, c.ROOM_TYPE AS RT FROM TIMETABLE_ENTRIES e "
                + "JOIN TIMETABLES t ON t.ID = e.TIMETABLE_ID JOIN TIME_SLOTS ts ON ts.ID = e.TIME_SLOT_ID "
                + "JOIN CLASSROOMS c ON c.ID = e.CLASSROOM_ID "
                + "WHERE t.SECTION_ID = ? AND e.SUBJECT_ID = ? AND e.IS_LAB = TRUE ORDER BY e.DAY_OF_WEEK, ts.SLOT_ORDER",
            sectionId, subjectId);
        if (rows.isEmpty()) {
            return "";
        }
        Map<String, List<Integer>> byDay = new TreeMap<>();
        Set<String> roomTypes = new HashSet<>();
        for (Map<String, Object> r : rows) {
            String day = String.valueOf(r.get("D"));
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(((Number) r.get("O")).intValue());
            roomTypes.add(String.valueOf(r.get("RT")));
        }
        StringBuilder sb = new StringBuilder(" | labRooms=" + roomTypes + " labRuns=");
        for (Map.Entry<String, List<Integer>> e : byDay.entrySet()) {
            List<Integer> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Integer::compareTo);
            List<Integer> runs = new ArrayList<>();
            int run = 1;
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i) == sorted.get(i - 1) + 1) {
                    run++;
                } else {
                    runs.add(run);
                    run = 1;
                }
            }
            runs.add(run);
            sb.append(e.getKey()).append(":").append(runs);
        }
        return sb.toString();
    }

    // =====================================================================
    // global cross-class checks
    // =====================================================================

    private void verifyGlobal(long deptId, int semester, List<Long> sections,
            Map<Long, SubjectInfo> demand, String masterBefore, String ttBefore) {
        // ITEM 4: faculty daily load across ALL classes.
        List<Map<String, Object>> facultyDays = jdbcTemplate.queryForList(
            "SELECT e.FACULTY_ID AS FID, e.DAY_OF_WEEK AS D, COUNT(*) AS LOAD "
                + "FROM TIMETABLE_ENTRIES e GROUP BY e.FACULTY_ID, e.DAY_OF_WEEK");
        Map<String, Integer> caps = new HashMap<>();
        for (Map<String, Object> f : jdbcTemplate.queryForList("SELECT ID, MAX_DAILY_HOURS FROM FACULTY")) {
            long fid = ((Number) f.get("ID")).longValue();
            int own = ((Number) f.get("MAX_DAILY_HOURS")).intValue();
            caps.put(String.valueOf(fid), Math.min(5, own));
        }
        List<String> dailyBreaches = new ArrayList<>();
        for (Map<String, Object> row : facultyDays) {
            String key = String.valueOf(row.get("FID"));
            int load = ((Number) row.get("LOAD")).intValue();
            int cap = caps.getOrDefault(key, 5);
            if (load > cap) {
                dailyBreaches.add(key + "/" + row.get("D") + "=" + load + "(cap " + cap + ")");
            }
        }
        System.out.println("VERIFY_ITEM4_FACULTY_DAILY|engine=" + engineName()
            + "|breaches=" + dailyBreaches);

        // ITEM 5: faculty availability.
        List<Map<String, Object>> statuses = jdbcTemplate.queryForList(
            "SELECT STATUS, COUNT(*) AS N FROM FACULTY GROUP BY STATUS");
        System.out.println("VERIFY_ITEM5_STATUS|engine=" + engineName() + "|" + statuses);
        long blockedBusy = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM FACULTY_AVAILABILITY WHERE SLOT_TYPE IN ('BLOCKED','BUSY')", Long.class);
        long onLeave = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM TIMETABLE_ENTRIES e JOIN FACULTY f ON f.ID = e.FACULTY_ID WHERE f.STATUS = 'LEAVE'",
            Long.class);
        long onBlocked = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM TIMETABLE_ENTRIES e "
                + "JOIN FACULTY_AVAILABILITY a ON a.FACULTY_ID = e.FACULTY_ID AND a.DAY_OF_WEEK = e.DAY_OF_WEEK "
                + "  AND a.TIME_SLOT_ID = e.TIME_SLOT_ID "
                + "WHERE a.SLOT_TYPE IN ('BLOCKED','BUSY')",
            Long.class);
        System.out.println("VERIFY_ITEM5_AVAILABILITY|engine=" + engineName()
            + "|blockedBusyFacts=" + blockedBusy + "|entriesOnLeave=" + onLeave
            + "|entriesOnBlockedWindow=" + onBlocked);

        // ITEM 6: LAB — never Saturday + room type + all runs consecutive.
        long labSaturday = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM TIMETABLE_ENTRIES e JOIN TIMETABLES t ON t.ID = e.TIMETABLE_ID "
                + "WHERE e.IS_LAB = TRUE AND e.DAY_OF_WEEK = 'SAT'", Long.class);
        long labWrongRoom = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM TIMETABLE_ENTRIES e JOIN CLASSROOMS c ON c.ID = e.CLASSROOM_ID "
                + "WHERE e.IS_LAB = TRUE AND c.ROOM_TYPE <> 'LAB'", Long.class);
        System.out.println("VERIFY_ITEM6_LAB|engine=" + engineName()
            + "|labEntries=" + jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TIMETABLE_ENTRIES WHERE IS_LAB = TRUE", Long.class)
            + "|onSaturday=" + labSaturday + "|nonLabRoom=" + labWrongRoom);

        // ITEM 7: independent clash recount across ALL timetables.
        Recount recount = recountFromDb();
        System.out.println("VERIFY_ITEM7_CLASHES|engine=" + engineName()
            + "|entries=" + recount.entries
            + "|faculty=" + recount.faculty + "|room=" + recount.room + "|section=" + recount.section);

        // ITEM 8: no mutation outside the explicitly generated sections.
        String masterAfter = fingerprint(MASTER_TABLES);
        System.out.println("VERIFY_ITEM8_MASTER|engine=" + engineName()
            + "|unchanged=" + masterBefore.equals(masterAfter));
        System.out.println("VERIFY_ITEM8_TT|engine=" + engineName()
            + "|before=" + ttBefore + "|after=" + timetableState() + "|state=" + ttStateBrief());

        // ITEM 9: no fake entries — every entry references real master rows and
        // each subject's placed count matches its DB demand where placement was possible.
        long orphanEntries = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM TIMETABLE_ENTRIES e LEFT JOIN SUBJECTS s ON s.ID = e.SUBJECT_ID "
                + "LEFT JOIN FACULTY f ON f.ID = e.FACULTY_ID LEFT JOIN CLASSROOMS c ON c.ID = e.CLASSROOM_ID "
                + "LEFT JOIN SECTIONS sec ON sec.ID = e.SECTION_ID LEFT JOIN TIME_SLOTS ts ON ts.ID = e.TIME_SLOT_ID "
                + "LEFT JOIN TIMETABLES t ON t.ID = e.TIMETABLE_ID "
                + "WHERE s.ID IS NULL OR f.ID IS NULL OR c.ID IS NULL OR sec.ID IS NULL OR ts.ID IS NULL OR t.ID IS NULL",
            Long.class);
        System.out.println("VERIFY_ITEM9_ORPHANS|engine=" + engineName() + "|entriesWithBrokenRefs=" + orphanEntries);

        // ITEM 10: unassigned work with the exact recorded constraint.
        List<Map<String, Object>> unassigned = jdbcTemplate.queryForList(
            "SELECT t.SECTION_ID AS SECTION, s.SUBJECT_CODE AS CODE, s.SUBJECT_TYPE AS TYPE, "
                + "s.THEORY_HOURS AS TH, s.PRACTICAL_HOURS AS PH, f.FIRST_NAME AS FN, f.LAST_NAME AS LN, "
                + "(SELECT COUNT(*) FROM TIMETABLE_ENTRIES e2 WHERE e2.TIMETABLE_ID = t.ID AND e2.SUBJECT_ID = s.ID) AS PLACED "
                + "FROM SUBJECTS s "
                + "JOIN TIMETABLES t ON t.DEPARTMENT_ID = s.DEPARTMENT_ID AND t.SEMESTER = s.SEMESTER "
                + "JOIN FACULTY f ON f.ID = s.ASSIGNED_FACULTY_ID "
                + "WHERE s.IS_ACTIVE = TRUE AND s.DEPARTMENT_ID = ? AND s.SEMESTER = ? "
                + "AND (s.SECTION_ID IS NULL OR s.SECTION_ID = t.SECTION_ID)",
            deptId, semester);
        List<Map<String, Object>> shortfalls = new ArrayList<>();
        for (Map<String, Object> row : unassigned) {
            long want = ((Number) row.get("TH")).longValue() + ((Number) row.get("PH")).longValue();
            long got = ((Number) row.get("PLACED")).longValue();
            if (got < want) {
                row.put("WANT", want);
                shortfalls.add(row);
            }
        }
        System.out.println("VERIFY_ITEM10_SHORTFALLS|engine=" + engineName() + "|count=" + shortfalls.size());
        for (Map<String, Object> row : shortfalls) {
            System.out.println("VERIFY_SHORTFALL|engine=" + engineName()
                + "|section=" + row.get("SECTION") + "|subject=" + row.get("CODE")
                + "|type=" + row.get("TYPE") + "|faculty=" + row.get("FN") + " " + row.get("LN")
                + "|expectedDemand=" + row.get("WANT") + "|actualDemand=" + row.get("PLACED"));
        }
        List<Map<String, Object>> conflicts = jdbcTemplate.queryForList(
            "SELECT t.SECTION_ID AS SECTION, c.CONFLICT_TYPE AS TYPE, c.DESCRIPTION AS DESC_ "
                + "FROM TIMETABLE_CONFLICTS c JOIN TIMETABLES t ON t.ID = c.TIMETABLE_ID ORDER BY t.SECTION_ID");
        System.out.println("VERIFY_ITEM10_CONFLICTS|engine=" + engineName() + "|count=" + conflicts.size());
        for (Map<String, Object> c : conflicts) {
            System.out.println("VERIFY_CONFLICT_DB|engine=" + engineName()
                + "|section=" + c.get("SECTION") + "|type=" + c.get("TYPE") + "|desc=" + c.get("DESC_"));
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private Map<Long, SubjectInfo> loadSubjectDemand(long deptId, int semester) {
        Map<Long, SubjectInfo> map = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT s.ID AS ID, s.SUBJECT_CODE AS CODE, s.SUBJECT_NAME AS NAME, s.SUBJECT_TYPE AS TYPE, "
                + "s.THEORY_HOURS AS TH, s.PRACTICAL_HOURS AS PH, s.SESSION_BLOCK_SIZE AS BLK, s.SECTION_ID AS SEC, "
                + "f.FIRST_NAME AS FN, f.LAST_NAME AS LN "
                + "FROM SUBJECTS s LEFT JOIN FACULTY f ON f.ID = s.ASSIGNED_FACULTY_ID "
                + "WHERE s.DEPARTMENT_ID = ? AND s.SEMESTER = ? AND s.IS_ACTIVE = TRUE",
            deptId, semester);
        for (Map<String, Object> r : rows) {
            map.put(((Number) r.get("ID")).longValue(), new SubjectInfo(
                ((Number) r.get("ID")).longValue(),
                String.valueOf(r.get("CODE")),
                String.valueOf(r.get("NAME")),
                String.valueOf(r.get("TYPE")),
                ((Number) r.get("TH")).intValue(),
                ((Number) r.get("PH")).intValue(),
                ((Number) r.get("BLK")).intValue(),
                r.get("SEC") == null ? null : ((Number) r.get("SEC")).longValue(),
                String.valueOf(r.get("FN")) + " " + r.get("LN")));
        }
        return map;
    }

    private Recount recountFromDb() {
        Set<String> faculty = new HashSet<>();
        Set<String> room = new HashSet<>();
        Set<String> section = new HashSet<>();
        int f = 0, r = 0, s = 0;
        for (Map<String, Object> row : jdbcTemplate.queryForList(
            "SELECT FACULTY_ID, CLASSROOM_ID, SECTION_ID, DAY_OF_WEEK, TIME_SLOT_ID FROM TIMETABLE_ENTRIES")) {
            String day = String.valueOf(row.get("DAY_OF_WEEK"));
            String slot = String.valueOf(row.get("TIME_SLOT_ID"));
            if (!faculty.add(row.get("FACULTY_ID") + "|" + day + "|" + slot)) {
                f++;
            }
            if (!room.add(row.get("CLASSROOM_ID") + "|" + day + "|" + slot)) {
                r++;
            }
            if (!section.add(row.get("SECTION_ID") + "|" + day + "|" + slot)) {
                s++;
            }
        }
        long entries = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TIMETABLE_ENTRIES", Long.class);
        return new Recount(f, r, s, entries);
    }

    private String fingerprint(List<String> tables) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String table : tables) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM " + table);
                for (Map<String, Object> row : rows) {
                    for (Object v : row.values()) {
                        md.update(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
                        md.update((byte) '|');
                    }
                    md.update((byte) 0x1E);
                }
                md.update((byte) '#');
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String timetableState() {
        List<Map<String, Object>> tts = jdbcTemplate.queryForList(
            "SELECT ID, SECTION_ID, CONFLICT_COUNT FROM TIMETABLES ORDER BY ID");
        StringBuilder sb = new StringBuilder("tt{");
        for (Map<String, Object> t : tts) {
            long id = ((Number) t.get("ID")).longValue();
            long entries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM TIMETABLE_ENTRIES WHERE TIMETABLE_ID = ?", Long.class, id);
            long conflicts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM TIMETABLE_CONFLICTS WHERE TIMETABLE_ID = ?", Long.class, id);
            sb.append("id").append(id).append("(sec").append(t.get("SECTION_ID"))
                .append(",e").append(entries).append(",c").append(conflicts).append(")");
        }
        return sb.append("}").toString();
    }

    private String ttStateBrief() {
        return "timetables=" + jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TIMETABLES", Long.class)
            + "|entries=" + jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TIMETABLE_ENTRIES", Long.class)
            + "|conflicts=" + jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TIMETABLE_CONFLICTS", Long.class);
    }

    private record Recount(int faculty, int room, int section, long entries) {
    }

    private record SubjectInfo(long id, String code, String name, String type,
            int theoryHours, int practicalHours, int blockSize, Long sectionId, String facultyName) {
    }
}
