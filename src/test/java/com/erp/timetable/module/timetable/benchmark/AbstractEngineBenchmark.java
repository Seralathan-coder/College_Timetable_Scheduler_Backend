package com.erp.timetable.module.timetable.benchmark;

import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.availability.repository.TimeSlotRepository;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.department.repository.SectionRepository;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.timetable.dto.GenerateTimetableRequest;
import com.erp.timetable.module.timetable.dto.TimetableConflictDto;
import com.erp.timetable.module.timetable.dto.TimetableEntryDto;
import com.erp.timetable.module.timetable.dto.TimetableResponse;
import com.erp.timetable.module.timetable.engine.shared.CurriculumDataLoader;
import com.erp.timetable.module.timetable.engine.shared.SubjectDemandService;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.service.TimetableService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 — reusable A/B benchmark harness for the two production scheduling
 * engines.
 *
 * <p>Each concrete subclass pins one engine via
 * {@code timetable.scheduler.engine} (Greedy or Timefold) and a dedicated H2
 * database. The benchmark drives the <b>real production scheduling path</b> —
 * {@link TimetableService#generateTimetable(GenerateTimetableRequest)} — which
 * is exactly what the REST controller calls: service validation, engine,
 * result mapping and persistence in one transaction. It does not use any test
 * utility; the only difference from an HTTP call is the JSON boundary, which is
 * not part of the scheduling pipeline.
 *
 * <p>For each dataset the harness:
 * <ol>
 *   <li>loads the dataset dump,</li>
 *   <li>captures the input facts (subjects, demand, faculty, rooms, windows),</li>
 *   <li>runs one unmeasured warm-up generation, then {@value #MEASURED_RUNS}
 *       measured generations, resetting only the timetable tables between runs
 *       so master data is byte-identical every run (input parity),</li>
 *   <li>records per-run input/output/performance metrics and prints
 *       {@code BENCH_RUN} lines plus a {@code BENCH_SUMMARY} with min / max /
 *       average / median,</li>
 *   <li>re-counts faculty / room / section clashes from the returned entries
 *       (an engine-independent re-check, not the engines' own conflict records).</li>
 * </ol>
 *
 * <p>Only the TT1 dataset currently exists. Future datasets are added by
 * dropping a dump under {@code src/test/resources/dataset/} and returning a new
 * {@link BenchmarkDataset} from {@link #datasets()}.
 *
 * <p>No production code is modified, no engine is tuned, the default engine is
 * unchanged and the Greedy engine stays available. The whole test transaction
 * rolls back.
 */
@SpringBootTest
@ActiveProfiles("h2")
@WithMockUser(roles = "HOD")
@Transactional
@ExtendWith(OutputCaptureExtension.class)
public abstract class AbstractEngineBenchmark {

    protected static final int MEASURED_RUNS = 5;
    protected static final int WARMUP_RUNS = 1;
    protected static final int LAB_BLOCK_SIZE = 3; // mirrors SubjectDemandService + both engines
    protected static final int N_A = -1;           // "not applicable" for metrics an engine cannot produce

    private static final Pattern TIMING_PATTERN = Pattern.compile("(\\d+)\\s*ms");
    private static final Pattern SOLVER_SCORE_PATTERN = Pattern.compile(
        "Solver Score\\s*:\\s*([+-]?\\d+)hard(?:/([+-]?\\d+)soft)?");

    @Autowired
    protected TimetableService timetableService;

    @Autowired
    protected CurriculumDataLoader curriculumDataLoader;

    @Autowired
    protected SubjectDemandService subjectDemandService;

    @Autowired
    protected DepartmentRepository departmentRepository;

    @Autowired
    protected SectionRepository sectionRepository;

    @Autowired
    protected FacultyRepository facultyRepository;

    @Autowired
    protected ClassroomRepository classroomRepository;

    @Autowired
    protected TimeSlotRepository timeSlotRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @PersistenceContext
    protected EntityManager entityManager;

    /** Engine under test. */
    protected abstract String engineName();

    /** Datasets to benchmark (only TT1 exists today; add future dumps here). */
    protected List<BenchmarkDataset> datasets() {
        return List.of(BenchmarkDataset.TT1());
    }

    @Test
    void benchmarkThroughProductionSchedulingPath(CapturedOutput output) {
        for (BenchmarkDataset ds : datasets()) {
            loadDump(ds);
            entityManager.clear();
            runBenchmarkForDataset(ds, output);
        }
    }

    // =====================================================================
    // benchmark driver
    // =====================================================================

    private void runBenchmarkForDataset(BenchmarkDataset ds, CapturedOutput output) {
        InputMetrics input = captureInputMetrics(ds);

        resetTimetableTables();
        for (int w = 0; w < WARMUP_RUNS; w++) {
            runOnce(ds, input, output);
        }

        List<RunResult> measured = new ArrayList<>();
        for (int r = 0; r < MEASURED_RUNS; r++) {
            resetTimetableTables();
            measured.add(runOnce(ds, input, output));
        }

        recordHeapSnapshot(ds);
        printSummary(ds, measured);
    }

    private void loadDump(BenchmarkDataset ds) {
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("RUNSCRIPT FROM 'classpath:" + ds.dumpResource() + "'");
    }

    /**
     * Resets only the timetable tables so every measured run starts from the
     * same empty timetable state while the master data (subjects, faculty,
     * rooms, slots, sections) stays untouched — identical input for every run.
     */
    private void resetTimetableTables() {
        entityManager.clear();
        jdbcTemplate.execute("DELETE FROM TIMETABLE_CONFLICTS");
        jdbcTemplate.execute("DELETE FROM TIMETABLE_ENTRIES");
        jdbcTemplate.execute("DELETE FROM TIMETABLES");
    }

    // =====================================================================
    // input facts (from the live, loaded dataset)
    // =====================================================================

    private InputMetrics captureInputMetrics(BenchmarkDataset ds) {
        Department dept = departmentRepository.findById(ds.departmentId()).orElseThrow();
        Section section = sectionRepository.findById(ds.sectionId()).orElseThrow();
        Timetable probe = Timetable.builder()
            .department(dept)
            .section(section)
            .semester(ds.semester())
            .build();

        List<Subject> subjects = curriculumDataLoader.loadSubjectsForTimetable(probe);
        Map<Long, Integer> weekly = subjectDemandService.calculateWeeklyHours(subjects);
        int requested = 0;
        for (Subject s : subjects) {
            int w = weekly.getOrDefault(s.getId(), 0);
            requested += "LAB".equalsIgnoreCase(s.getSubjectType()) ? w * LAB_BLOCK_SIZE : w;
        }

        int faculty = facultyRepository.findAll().size();
        int rooms = classroomRepository.findByStatus("AVAILABLE").size();
        int teachingSlots = (int) timeSlotRepository.findAllByOrderBySlotOrderAsc().stream()
            .filter(ts -> !Boolean.TRUE.equals(ts.getIsBreak()))
            .count();
        int windows = teachingSlots * SubjectDemandService.WORKING_DAYS.size();

        return new InputMetrics(subjects.size(), requested, faculty, rooms, windows, 1);
    }

    // =====================================================================
    // one measured run
    // =====================================================================

    private RunResult runOnce(BenchmarkDataset ds, InputMetrics input, CapturedOutput output) {
        String before = output.getAll();

        GenerateTimetableRequest request = GenerateTimetableRequest.builder()
            .departmentId(ds.departmentId())
            .sectionId(ds.sectionId())
            .semester(ds.semester())
            .academicSession(ds.academicSession())
            .build();

        long start = System.nanoTime();
        TimetableResponse resp = timetableService.generateTimetable(request);
        long totalMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        String runLogs = output.getAll().substring(before.length());

        List<TimetableEntryDto> entries = resp.getEntries();
        int scheduled = entries.size();
        int unassigned = input.requestedLessons() - scheduled;
        int freeWindows = input.windows() - scheduled;

        int[] hardSoft = parseHardSoft(runLogs);
        int hard = hardSoft != null ? hardSoft[0] : N_A;
        int soft = hardSoft != null ? hardSoft[1] : N_A;

        int conflicts = resp.getConflictCount();
        int solverInfeasible = 0;
        for (TimetableConflictDto c : resp.getConflicts()) {
            if ("SOLVER_INFEASIBLE".equals(c.getConflictType())) {
                solverInfeasible++;
            }
        }

        Clashes clashes = countClashes(entries);

        long engineMs = parseMillis(runLogs, "Engine total");
        long solverMs = parseMillis(runLogs, "Solved in");
        long mappingMs = parseMillis(runLogs, "Prepared planning model in");
        long applyMs = parseMillis(runLogs, "Applied solver result in");
        long persistMs = engineMs > 0 ? Math.max(0L, totalMs - engineMs) : N_A;

        assertTrue(scheduled > 0, "benchmark harness must observe scheduled entries (engine=" + engineName() + ")");
        assertTrue(totalMs > 0, "benchmark harness must observe a positive generation time");

        System.out.println("BENCH_RUN|engine=" + engineName()
            + "|dataset=" + ds.name()
            + "|requested=" + input.requestedLessons()
            + "|scheduled=" + scheduled
            + "|unassigned=" + unassigned
            + "|windows=" + input.windows()
            + "|free=" + freeWindows
            + "|hard=" + (hard == N_A ? "-" : hard)
            + "|soft=" + (soft == N_A ? "-" : soft)
            + "|conflicts=" + conflicts
            + "|solverInfeasible=" + solverInfeasible
            + "|facultyClashes=" + clashes.faculty()
            + "|roomClashes=" + clashes.room()
            + "|sectionClashes=" + clashes.section()
            + "|totalMs=" + totalMs
            + "|solverMs=" + (solverMs > 0 ? solverMs : "-")
            + "|mappingMs=" + (mappingMs > 0 ? mappingMs : "-")
            + "|applyMs=" + (applyMs > 0 ? applyMs : "-")
            + "|persistMs=" + (persistMs >= 0 ? persistMs : "-"));

        return new RunResult(scheduled, unassigned, freeWindows, hard, soft, conflicts,
            solverInfeasible, clashes.faculty(), clashes.room(), clashes.section(),
            totalMs, solverMs, mappingMs, applyMs, persistMs);
    }

    /** Engine-independent re-count of actual double-bookings in the returned entries. */
    private Clashes countClashes(List<TimetableEntryDto> entries) {
        Map<String, Long> faculty = new HashMap<>();
        Map<String, Long> room = new HashMap<>();
        Map<String, Long> section = new HashMap<>();
        int facultyClashes = 0;
        int roomClashes = 0;
        int sectionClashes = 0;
        for (TimetableEntryDto e : entries) {
            String window = e.getDayOfWeek() + "|" + e.getTimeSlotId();
            facultyClashes += bump(faculty, e.getFacultyId() + "|" + window);
            roomClashes += bump(room, e.getClassroomId() + "|" + window);
            sectionClashes += bump(section, window);
        }
        return new Clashes(facultyClashes, roomClashes, sectionClashes);
    }

    private static long bump(Map<String, Long> counts, String key) {
        long n = counts.merge(key, 1L, Long::sum);
        return n - 1;
    }

    // =====================================================================
    // log parsing (Timefold emits timing + solver-score lines; Greedy emits none)
    // =====================================================================

    protected static long parseMillis(String logs, String label) {
        Matcher m = TIMING_PATTERN.matcher(logs);
        int from = 0;
        while (m.find(from)) {
            int digitStart = m.start(1);
            if (logs.substring(Math.max(0, digitStart - 120), digitStart).contains(label)) {
                return Long.parseLong(m.group(1));
            }
            from = m.end();
        }
        return 0L;
    }

    private static int[] parseHardSoft(String logs) {
        Matcher m = SOLVER_SCORE_PATTERN.matcher(logs);
        if (m.find()) {
            int hard = Integer.parseInt(m.group(1));
            int soft = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            return new int[]{hard, soft};
        }
        return null;
    }

    // =====================================================================
    // summary + heap
    // =====================================================================

    private void recordHeapSnapshot(BenchmarkDataset ds) {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        System.out.println("BENCH_MEM|engine=" + engineName()
            + "|dataset=" + ds.name()
            + "|jvmUsedAfterRunsMb=" + usedMb
            + "|jvmMaxMb=" + rt.maxMemory() / 1024 / 1024
            + "|note=shared-JVM heap (both engines in one JVM); indicative only, not per-engine isolation");
    }

    private void printSummary(BenchmarkDataset ds, List<RunResult> runs) {
        long[] total = runs.stream().mapToLong(RunResult::totalMs).toArray();
        long[] solver = runs.stream().mapToLong(RunResult::solverMs).filter(v -> v > 0).toArray();
        long[] mapping = runs.stream().mapToLong(RunResult::mappingMs).filter(v -> v > 0).toArray();
        long[] apply = runs.stream().mapToLong(RunResult::applyMs).filter(v -> v > 0).toArray();
        long[] persist = runs.stream().mapToLong(RunResult::persistMs).filter(v -> v >= 0).toArray();
        int[] scheduled = runs.stream().mapToInt(RunResult::scheduled).toArray();
        int[] conflicts = runs.stream().mapToInt(RunResult::conflicts).toArray();

        System.out.println("BENCH_SUMMARY|engine=" + engineName()
            + "|dataset=" + ds.name()
            + "|runs=" + runs.size()
            + "|totalMs(min=" + min(total) + ",max=" + max(total) + ",avg=" + avg(total) + ",median=" + median(total) + ")"
            + "|solverMs(" + (solver.length == 0 ? "-" : "min=" + min(solver) + ",max=" + max(solver)
                + ",avg=" + avg(solver) + ",median=" + median(solver)) + ")"
            + "|mappingMs(" + (mapping.length == 0 ? "-" : "min=" + min(mapping) + ",max=" + max(mapping)
                + ",avg=" + avg(mapping) + ",median=" + median(mapping)) + ")"
            + "|applyMs(" + (apply.length == 0 ? "-" : "min=" + min(apply) + ",max=" + max(apply)
                + ",avg=" + avg(apply) + ",median=" + median(apply)) + ")"
            + "|persistMs(" + (persist.length == 0 ? "-" : "min=" + min(persist) + ",max=" + max(persist)
                + ",avg=" + avg(persist) + ",median=" + median(persist)) + ")"
            + "|scheduled(min=" + min(scheduled) + ",max=" + max(scheduled) + ",avg=" + avg(scheduled)
                + ",median=" + median(scheduled) + ")"
            + "|conflicts(min=" + min(conflicts) + ",max=" + max(conflicts) + ",avg=" + avg(conflicts)
                + ",median=" + median(conflicts) + ")");
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

    private static double median(long[] a) {
        long[] sorted = Arrays.stream(a).sorted().toArray();
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0
            ? (sorted[mid - 1] + sorted[mid]) / 2.0
            : sorted[mid];
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

    private static double median(int[] a) {
        int[] sorted = Arrays.stream(a).sorted().toArray();
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0
            ? (sorted[mid - 1] + sorted[mid]) / 2.0
            : sorted[mid];
    }

    // =====================================================================
    // value types
    // =====================================================================

    private record InputMetrics(int subjectCount, int requestedLessons, int facultyCount,
            int roomCount, int windows, int sectionCount) {
    }

    private record Clashes(int faculty, int room, int section) {
    }

    private record RunResult(int scheduled, int unassigned, int freeWindows, int hardScore,
            int softScore, int conflicts, int solverInfeasible, int facultyClashes,
            int roomClashes, int sectionClashes, long totalMs, long solverMs, long mappingMs,
            long applyMs, long persistMs) {
    }
}
