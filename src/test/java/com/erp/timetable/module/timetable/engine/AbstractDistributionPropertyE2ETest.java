package com.erp.timetable.module.timetable.engine;

import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import com.erp.timetable.module.timetable.repository.TimetableRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dynamic distribution-property contract, run identically for BOTH engines
 * (greedy / timefold) on temporary, isolated records.
 *
 * <p>The properties are deliberately independent of any fixed day or pattern —
 * they follow the 2-period daily rule (a NORMAL subject holds at most 2 periods
 * a day, back-to-back when it holds 2) and must hold across the whole demand
 * spectrum:
 * <ul>
 *   <li>low / medium / high weekly demands ({@code 1..14}), odd and even,</li>
 *   <li>demands above the 6 x 2 ceiling, which still keep every period placed,</li>
 *   <li>sessionBlockSize 1 / 2 must not change the distribution,</li>
 *   <li>a mixed THEORY+practical subject keeps its theory spread and its
 *       practical as one block (the Greedy/Timefold distribution parity),</li>
 *   <li>multiple subjects balance the whole-section day load while each follows
 *       the pair/single pattern, with at most one back-to-back subject per day.</li>
 * </ul>
 *
 * <p>No mentor-created subject, faculty, classroom, department or section is
 * ever touched; records are deleted explicitly and the transaction rolls back.
 */
@SpringBootTest
@ActiveProfiles("h2")
@Transactional
public abstract class AbstractDistributionPropertyE2ETest {

    private static final AtomicInteger COUNTER = new AtomicInteger(1);
    private static final String SESSION = "2025-2026 EVEN";

    @Autowired
    protected ScheduleEngine engine;

    @Autowired
    protected DepartmentRepository departmentRepository;

    @Autowired
    protected FacultyRepository facultyRepository;

    @Autowired
    protected SubjectRepository subjectRepository;

    @Autowired
    protected TimetableRepository timetableRepository;

    protected abstract String engineName();

    // ── TEST 1: the 2-period daily rule across the whole demand spectrum ────

    /**
     * A single NORMAL (THEORY) subject alone in its section follows the
     * 2-period daily rule for every weekly demand: a subject holds at most 2
     * periods on a day, and when it holds 2 they are back-to-back. The teaching
     * days come from the shared formula
     * {@code doubleDays = max(0, H - 5)}, {@code singleDays = H - 2*doubleDays},
     * so 5→1+1+1+1+1, 6→2+1+1+1+1, 7→2+2+1+1+1, 8→2+2+2+1+1 (5 days each) and
     * 11→5 pairs + 1 single (6 days). Low (1-2), medium (4-5), high (9-12) and
     * odd/even demands are all covered by the same rule.
     */
    @Test
    void theoryDemand_followsTwoPerDayRule_acrossAllDemandShapes() {
        for (int theory : List.of(1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12)) {
            Department dept = newDepartment("DISLOW");
            Section section = dept.getAcademicYears().get(0).getSections().get(0);
            Faculty faculty = newFaculty(dept, "DISFAC");
            Subject subject = newSubject(dept, section, faculty, "DIST", "Dist Subject", "THEORY", theory, 0, 1);
            try {
                Timetable timetable = generateForSection(dept, section);
                long days = theoryDaysOf(timetable, subject);
                int expected = expectedTeachingDays(theory);
                assertEquals(theory, entriesOf(timetable, subject).size(),
                    engineName() + " T=" + theory + ": all " + theory + " periods must be placed");
                assertEquals(expected, days,
                    engineName() + " T=" + theory + ": teaching days must follow the pair/single rule");
                assertTwoPerDayRule(timetable, subject, "T=" + theory);
                assertClean(timetable, "T=" + theory);
                assertNoClashes(timetable.getEntries());
                assertDailyFacultyCap(timetable);
                printMetric("two-per-day|engine=" + engineName() + "|theory=" + theory + "|days=" + days + "|expected=" + expected);
            } finally {
                cleanup(dept, List.of(subject), List.of(faculty));
            }
        }
    }

    // ── TEST 2: the top of the satisfiable demand range ──────────────────

    /**
     * The 2-period daily rule caps a subject at 6 days x 2 = 12 weekly periods,
     * so 9..12 hours is the top of the satisfiable range: every period must be
     * placed AND the rule must still hold exactly (9/10 → 5 days, 11 → 6 days
     * with 5 pairs + 1 single, 12 → 6 days of pairs).
     *
     * <p>A demand ABOVE 12 is physically unsatisfiable under the rule and the two
     * engines deliberately report it differently, so it is not part of this shared
     * contract: Greedy's mop-up fills the surplus period and leaves one over-full
     * day (keeping the section at its full 42 slots), while Timefold's hard rule
     * leaves the surplus lesson unassigned (hard rules outrank the unassigned
     * penalty). Neither may place a 3rd period silently in a normal case.
     */
    @Test
    void highDemand_upToTwelveStaysSatisfiableUnderTheTwoPerDayRule() {
        for (int theory : List.of(9, 10, 11, 12)) {
            Department dept = newDepartment("DISHIGH");
            Section section = dept.getAcademicYears().get(0).getSections().get(0);
            Faculty faculty = newFaculty(dept, "DISFAC");
            Subject subject = newSubject(dept, section, faculty, "DISH", "High Dist Subject", "THEORY", theory, 0, 1);
            try {
                Timetable timetable = generateForSection(dept, section);
                assertEquals(theory, entriesOf(timetable, subject).size(),
                    engineName() + " T=" + theory + ": every requested period must be placed");
                assertEquals(expectedTeachingDays(theory), theoryDaysOf(timetable, subject),
                    engineName() + " T=" + theory + ": teaching days must follow the pair/single rule");
                assertTwoPerDayRule(timetable, subject, "T=" + theory);
                assertEquals(expectedPairDays(theory), theoryPairDays(timetable, subject),
                    engineName() + " T=" + theory + ": consecutive-pair count");
                assertClean(timetable, "T=" + theory);
                assertNoClashes(timetable.getEntries());
                assertDailyFacultyCap(timetable);
                printMetric("high-demand|engine=" + engineName() + "|theory=" + theory
                    + "|days=" + theoryDaysOf(timetable, subject)
                    + "|pairs=" + theoryPairDays(timetable, subject));
            } finally {
                cleanup(dept, List.of(subject), List.of(faculty));
            }
        }
    }

    // ── TEST 3: the stored block size no longer changes the distribution ────

    /**
     * The pair/single pattern is a property of the WEEKLY HOURS, not of the
     * subject's stored {@code sessionBlockSize}: blockSize 1 (the database
     * default) and blockSize 2 produce the SAME distribution — 5 teaching days
     * for H=5..10 and 6 days for H=11 (5 pairs + 1 single).
     */
    @Test
    void theoryBlockSizes_doNotInflateTeachingDays() {
        Map<Integer, Integer> expectedDays = Map.of(
            5, 5,   // 0 pairs + 5 singles
            6, 5,   // 1 pair + 4 singles
            7, 5,   // 2 pairs + 3 singles
            8, 5,   // 3 pairs + 2 singles
            11, 6   // 5 pairs + 1 single
        );
        for (int block : List.of(1, 2)) {
            for (int theory : List.of(5, 6, 7, 8, 11)) {
                Department dept = newDepartment("DISBLK");
                Section section = dept.getAcademicYears().get(0).getSections().get(0);
                Faculty faculty = newFaculty(dept, "DISFAC");
                Subject subject = newSubject(dept, section, faculty, "DISB", "Block Dist Subject", "THEORY", theory, 0, block);
                try {
                    Timetable timetable = generateForSection(dept, section);
                    long days = theoryDaysOf(timetable, subject);
                    assertEquals(theory, entriesOf(timetable, subject).size(),
                        engineName() + " block=" + block + " T=" + theory + ": all periods must be placed");
                    assertEquals(expectedDays.get(theory), (int) days,
                        engineName() + " block=" + block + " T=" + theory + ": pair/single distribution");
                    assertTwoPerDayRule(timetable, subject, "block=" + block + " T=" + theory);
                    assertEquals(expectedPairDays(theory), theoryPairDays(timetable, subject),
                        engineName() + " block=" + block + " T=" + theory + ": consecutive-pair count");
                    assertClean(timetable, "block=" + block + " T=" + theory);
                    assertNoClashes(timetable.getEntries());
                    assertDailyFacultyCap(timetable);
                    printMetric("block-days|engine=" + engineName() + "|block=" + block + "|theory=" + theory + "|days=" + days);
                } finally {
                    cleanup(dept, List.of(subject), List.of(faculty));
                }
            }
        }
    }

    // ── TEST 4: mixed theory + practical ───────────────────────────────────

    /**
     * A THEORY subject (5 theory hours, block 2) with 2 practical hours.
     * The theory component follows the pair/single rule (H=5 → 5 single-period
     * days) and is INDEPENDENT of the practical component, which renders as one
     * strict 2-consecutive LAB session (Part 2: a lab always uses its full
     * practicalHours as one block).
     */
    @Test
    void mixedTheoryPractical_keepsTheorySinglePerDayAndLabAsOneBlock() {
        Department dept = newDepartment("DISMIX");
        Section section = dept.getAcademicYears().get(0).getSections().get(0);
        Faculty faculty = newFaculty(dept, "DISFAC");
        Subject subject = newSubject(dept, section, faculty, "DISM", "Mixed Dist Subject", "THEORY", 5, 2, 2);
        try {
            Timetable timetable = generateForSection(dept, section);
            assertEquals(5, theoryOf(timetable, subject),
                engineName() + ": 5 theory periods must be placed");
            assertEquals(2, labOf(timetable, subject),
                engineName() + ": 2 practical periods must be placed");
            // H=5: doubleDays=0, singleDays=5 → 5 theory days, one period each.
            assertEquals(5, theoryDaysOf(timetable, subject),
                engineName() + ": theory must spread across 5 single-period days");
            assertTwoPerDayRule(timetable, subject, "mixed");
            // Part 2: lab always uses full practicalHours as one block.
            assertEquals(List.of(2), labRunSizes(timetable, subject),
                engineName() + ": practical must render as one strict 2-consecutive session");
            assertClean(timetable, "mixed");
            assertLabRules(timetable);
            assertNoClashes(timetable.getEntries());
            assertDailyFacultyCap(timetable);
            printMetric("mixed|engine=" + engineName()
                + "|theory=5|practical=2|theoryDays=5|labRuns=" + labRunSizes(timetable, subject));
        } finally {
            cleanup(dept, List.of(subject), List.of(faculty));
        }
    }

    // ── TEST 5: multiple subjects balance the section days ──────────────────

    /**
     * Three 5-hour theory subjects (distinct faculties) must each spread over 5
     * DISTINCT days with exactly one period per day (H=5 → 1+1+1+1+1), and the
     * least-loaded day assignment must keep the whole-section day load balanced
     * instead of piling every subject onto the same days.
     */
    @Test
    void multipleSubjects_eachSpreadSinglePerDay_andBalanceSectionDays() {
        Department dept = newDepartment("DISBAL");
        Section section = dept.getAcademicYears().get(0).getSections().get(0);
        List<Faculty> faculties = new ArrayList<>();
        List<Subject> subjects = new ArrayList<>();
        try {
            for (int i = 1; i <= 3; i++) {
                Faculty f = newFaculty(dept, "DISF" + i);
                faculties.add(f);
                subjects.add(newSubject(dept, section, f, "DISB" + i, "Balanced Subject " + i, "THEORY", 5, 0, 1));
            }
            Timetable timetable = generateForSection(dept, section);
            assertEquals(15, timetable.getEntries().size(), "3 x 5 theory = 15");

            for (Subject s : subjects) {
                assertEquals(5, theoryDaysOf(timetable, s),
                    engineName() + ": each 5-hour subject must spread over 5 distinct days");
                assertTwoPerDayRule(timetable, s, s.getSubjectCode());
                assertEquals(0, theoryPairDays(timetable, s),
                    engineName() + ": a 5-hour subject has no surplus period, so no pair");
            }
            // Whole-section balance: 15 periods spread by least-loaded assignment.
            Map<String, Long> dayLoad = timetable.getEntries().stream()
                .collect(Collectors.groupingBy(TimetableEntry::getDayOfWeek, Collectors.counting()));
            assertTrue(dayLoad.size() >= 3,
                "3 spread subjects must not collapse onto fewer than 3 days: " + dayLoad);
            assertTrue(dayLoad.values().stream().allMatch(v -> v <= 4),
                "section day load must stay balanced (no day above 4 of 7): " + dayLoad);
            assertOneTheoryPairPerDay(timetable);

            assertClean(timetable, "balance");
            assertNoClashes(timetable.getEntries());
            assertDailyFacultyCap(timetable);
            printMetric("balance|engine=" + engineName() + "|subjects=3x5|days=" + dayLoad);
        } finally {
            cleanup(dept, subjects, faculties);
        }
    }

    // ── TEST 7: a 2-hour subject configured 2×CONSECUTIVE stays ONE pair ──

    /**
     * A subject explicitly configured {@code weeklyHours = 2} +
     * {@code sessionBlockSize = 2} must be scheduled as ONE back-to-back
     * 2-period block on ONE working day. It must NOT be spread as two single
     * periods on two days, and it must never exceed its 2-period weekly demand.
     *
     * <p>The control subject in the same section — 2 hours with the default
     * SINGLE block — must stay spread, proving the block comes from the explicit
     * configuration and is not granted to every 2-hour subject.
     */
    @Test
    void twoHourConsecutiveBlockSubject_staysOnePairWhileSingleBlockSubject_spreads() {
        Department dept = newDepartment("PAIRH");
        Section section = dept.getAcademicYears().get(0).getSections().get(0);
        List<Faculty> faculties = new ArrayList<>();
        List<Subject> subjects = new ArrayList<>();
        try {
            Faculty pairFaculty = newFaculty(dept, "PAIRF1");
            Faculty singleFaculty = newFaculty(dept, "PAIRF2");
            faculties.add(pairFaculty);
            faculties.add(singleFaculty);

            Subject consecutive = newSubject(dept, section, pairFaculty, "PAIRC",
                "Two Hour Consecutive Subject", "THEORY", 2, 0, 2);
            Subject single = newSubject(dept, section, singleFaculty, "PAIRS",
                "Two Hour Single Subject", "THEORY", 2, 0, 1);
            subjects.add(consecutive);
            subjects.add(single);

            Timetable timetable = generateForSection(dept, section);

            // ── the explicitly configured 2×CONSECUTIVE subject ──────────────
            List<TimetableEntry> pairEntries = entriesOf(timetable, consecutive);
            assertEquals(2, pairEntries.size(),
                engineName() + ": a 2-hour subject must place exactly 2 entries, never more: "
                    + pairEntries);
            assertEquals(2L, theoryOf(timetable, consecutive),
                engineName() + ": the weekly total must remain exactly 2: " + pairEntries);

            Set<String> pairDays = pairEntries.stream()
                .map(TimetableEntry::getDayOfWeek)
                .collect(Collectors.toSet());
            assertEquals(1, pairDays.size(),
                engineName() + ": both periods of an explicit 2xCONSECUTIVE subject must sit on ONE day: "
                    + pairEntries);

            List<Integer> pairOrders = pairEntries.stream()
                .map(e -> e.getTimeSlot().getSlotOrder())
                .sorted()
                .toList();
            assertEquals(2, pairOrders.size(), pairEntries.toString());
            assertEquals(1, pairOrders.get(1) - pairOrders.get(0),
                engineName() + ": the 2 periods must be CONSECUTIVE, got slots " + pairOrders);
            assertTrue(pairEntries.stream()
                    .noneMatch(e -> Boolean.TRUE.equals(e.getTimeSlot().getIsBreak())),
                engineName() + ": a pair may never use a break slot: " + pairEntries);

            // ── the control subject with the default SINGLE block ─────────────
            List<TimetableEntry> singleEntries = entriesOf(timetable, single);
            assertEquals(2, singleEntries.size(),
                engineName() + ": the control subject must also place exactly 2 entries: " + singleEntries);
            long singleDays = singleEntries.stream()
                .map(TimetableEntry::getDayOfWeek)
                .distinct()
                .count();
            assertEquals(2, singleDays,
                engineName() + ": a 2-hour SINGLE subject must stay spread over 2 distinct days, "
                    + "never be promoted to a pair: " + singleEntries);

            // The section-wide invariants still hold.
            assertEquals(4, timetable.getEntries().size(),
                engineName() + ": 2 + 2 = 4 entries in total");
            assertOneTheoryPairPerDay(timetable);
            assertClean(timetable, "2h-consecutive");
            assertNoClashes(timetable.getEntries());
            assertDailyFacultyCap(timetable);
            printMetric("2h-consecutive|engine=" + engineName()
                + "|pairDay=" + pairDays.iterator().next() + "|slots=" + pairOrders);
        } finally {
            cleanup(dept, subjects, faculties);
        }
    }

    // ── TEST 6: the full 5/6/7/8-hour curriculum in one section ────────────

    /**
     * The user's example curriculum in ONE section — 5, 6, 7 and 8-hour normal
     * subjects — must come out as 1+1+1+1+1, 2+1+1+1+1, 2+2+1+1+1 and
     * 2+2+2+1+1, with every pair on consecutive periods, at most one
     * back-to-back subject per day, and no conflict anywhere.
     */
    @Test
    void mixedDemandCurriculum_usesPairsPlusSinglesWithOnePairPerDay() {
        Department dept = newDepartment("DISCRS");
        Section section = dept.getAcademicYears().get(0).getSections().get(0);
        List<Faculty> faculties = new ArrayList<>();
        List<Subject> subjects = new ArrayList<>();
        try {
            Map<Integer, Integer> demand = new java.util.LinkedHashMap<>();
            demand.put(5, 0); // 5h → 1+1+1+1+1
            demand.put(6, 1); // 6h → 2+1+1+1+1
            demand.put(7, 2); // 7h → 2+2+1+1+1
            demand.put(8, 3); // 8h → 2+2+2+1+1
            int i = 0;
            for (Map.Entry<Integer, Integer> entry : demand.entrySet()) {
                Faculty f = newFaculty(dept, "DISFC" + i);
                faculties.add(f);
                subjects.add(newSubject(dept, section, f, "DISCR" + i,
                    "Curriculum Subject " + entry.getKey(), "THEORY", entry.getKey(), 0, 1));
                i++;
            }

            Timetable timetable = generateForSection(dept, section);
            assertEquals(26, timetable.getEntries().size(), "5+6+7+8 = 26 periods");
            for (Subject s : subjects) {
                int hours = demand.keySet().stream()
                    .filter(h -> h == s.getTheoryHours())
                    .findFirst().orElseThrow();
                assertEquals(hours, (int) theoryOf(timetable, s),
                    s.getSubjectCode() + ": every theory period must be placed");
                assertEquals(5, theoryDaysOf(timetable, s),
                    s.getSubjectCode() + ": H=" + hours + " must use 5 teaching days");
                assertTwoPerDayRule(timetable, s, s.getSubjectCode() + " H=" + hours);
                assertEquals(Math.max(0, hours - 5), theoryPairDays(timetable, s),
                    s.getSubjectCode() + ": H=" + hours + " must have max(0, H-5) consecutive pairs");
            }
            assertOneTheoryPairPerDay(timetable);
            assertClean(timetable, "curriculum");
            assertNoClashes(timetable.getEntries());
            assertDailyFacultyCap(timetable);
            printMetric("curriculum|engine=" + engineName() + "|days=" + timetable.getEntries().stream()
                .map(TimetableEntry::getDayOfWeek).distinct().count()
                + "|entries=" + timetable.getEntries().size());
        } finally {
            cleanup(dept, subjects, faculties);
        }
    }

    // ── shared assertions ───────────────────────────────────────────────────

    /**
     * A NORMAL subject never holds more than 2 periods on a day, and when it
     * holds 2 they are consecutive periods.
     */
    private void assertTwoPerDayRule(Timetable t, Subject s, String phase) {
        for (Map.Entry<String, List<Integer>> day : theoryOrdersByDay(t, s).entrySet()) {
            List<Integer> orders = day.getValue().stream().sorted().toList();
            assertTrue(orders.size() <= 2,
                engineName() + " " + phase + ": " + s.getSubjectCode() + " has " + orders.size()
                    + " periods on " + day.getKey() + " (" + orders + ") — max 2 per day");
            if (orders.size() == 2) {
                assertEquals(1, orders.get(1) - orders.get(0),
                    engineName() + " " + phase + ": " + s.getSubjectCode() + " holds 2 periods on "
                        + day.getKey() + " but they are not consecutive (" + orders + ")");
            }
        }
    }

    /**
     * One section + one day = at most ONE back-to-back NORMAL subject.
     */
    private void assertOneTheoryPairPerDay(Timetable t) {
        Map<String, Set<Long>> pairOwnersByDay = new java.util.HashMap<>();
        Map<String, Map<Long, List<Integer>>> ordersByDayAndSubject = t.getEntries().stream()
            .filter(e -> !e.isLab())
            .collect(Collectors.groupingBy(TimetableEntry::getDayOfWeek,
                Collectors.groupingBy(e -> e.getSubject().getId(),
                    Collectors.mapping(e -> e.getTimeSlot().getSlotOrder(), Collectors.toList()))));
        for (Map.Entry<String, Map<Long, List<Integer>>> day : ordersByDayAndSubject.entrySet()) {
            Set<Long> owners = new java.util.HashSet<>();
            for (Map.Entry<Long, List<Integer>> bySubject : day.getValue().entrySet()) {
                List<Integer> orders = bySubject.getValue().stream().sorted().toList();
                for (int k = 1; k < orders.size(); k++) {
                    if (orders.get(k) - orders.get(k - 1) == 1) {
                        owners.add(bySubject.getKey());
                    }
                }
            }
            pairOwnersByDay.put(day.getKey(), owners);
            assertTrue(owners.size() <= 1,
                engineName() + ": day " + day.getKey() + " hosts " + owners.size()
                    + " back-to-back subjects (max 1 allowed)");
        }
    }

    /** Number of days on which the subject holds a consecutive pair. */
    private long theoryPairDays(Timetable t, Subject s) {
        return theoryOrdersByDay(t, s).values().stream()
            .filter(orders -> orders.size() == 2
                && orders.stream().sorted().collect(Collectors.toList()).get(1)
                    - orders.stream().sorted().collect(Collectors.toList()).get(0) == 1)
            .count();
    }

    /** Highest number of theory periods the subject holds on any single day. */
    private long maxPeriodsOnAnyDay(Timetable t, Subject s) {
        return theoryOrdersByDay(t, s).values().stream()
            .mapToInt(List::size)
            .max()
            .orElse(0);
    }    private static Map<String, List<Integer>> theoryOrdersByDay(Timetable t, Subject s) {
        return entriesOf(t, s).stream()
            .filter(e -> !e.isLab())
            .collect(Collectors.groupingBy(TimetableEntry::getDayOfWeek,
                Collectors.mapping(e -> e.getTimeSlot().getSlotOrder(), Collectors.toList())));
    }

    /**
     * Teaching days a normal subject must use for {@code h} weekly periods:
     * {@code doubleDays = max(0, h - 5)} pairs and {@code h - 2*doubleDays}
     * singles, sharing days only when 2 pairs overshoot the demand.
     */
    private static int expectedTeachingDays(int h) {
        return expectedPairDays(h) + (h - expectedPairDays(h) * 2);
    }

    /**
     * Consecutive-pair days of a normal subject: {@code max(0, H - 5)} pairs,
     * reduced to {@code H / 2} when that many pairs would overshoot the demand
     * (H=11 → 5 pairs + 1 single).
     */
    private static int expectedPairDays(int h) {
        int pairDays = Math.max(0, h - 5);
        if (h - pairDays * 2 < 0) {
            pairDays = h / 2;
        }
        return pairDays;
    }

    private void assertClean(Timetable t, String phase) {
        assertTrue(t.getConflicts().isEmpty(),
            engineName() + " " + phase + ": no conflicts may remain on a feasible solve: " + t.getConflicts());
        assertEquals(0, t.getConflictCount(), engineName() + " " + phase + ": conflictCount must be 0");
    }

    private void assertLabRules(Timetable t) {
        for (TimetableEntry e : t.getEntries()) {
            if (e.isLab()) {
                assertEquals("LAB", e.getClassroom().getRoomType(),
                    engineName() + ": practical periods must use a LAB room");
                assertTrue(e.getClassroom().getCapacity() != null
                        && e.getClassroom().getCapacity() >= t.getSection().getStudentStrength(),
                    engineName() + ": LAB room capacity must be >= section strength");
                assertTrue(!"SAT".equals(e.getDayOfWeek()),
                    engineName() + ": no LAB session may land on Saturday");
            }
        }
    }

    private void assertDailyFacultyCap(Timetable t) {
        Map<String, Long> dailyLoad = t.getEntries().stream()
            .collect(Collectors.groupingBy(
                e -> e.getFaculty().getId() + "_" + e.getDayOfWeek(), Collectors.counting()));
        assertTrue(dailyLoad.values().stream().allMatch(v -> v <= 5),
            engineName() + ": faculty daily load exceeded the college-wide cap of 5: " + dailyLoad);
    }

    private void assertNoClashes(List<TimetableEntry> entries) {
        Set<String> sectionKeys = new HashSet<>();
        Set<String> facultyKeys = new HashSet<>();
        Set<String> roomKeys = new HashSet<>();
        for (TimetableEntry e : entries) {
            String window = e.getDayOfWeek() + "_" + e.getTimeSlot().getId();
            assertTrue(sectionKeys.add(e.getSection().getId() + "_" + window),
                engineName() + ": section double-booked at " + window);
            assertTrue(facultyKeys.add(e.getFaculty().getId() + "_" + window),
                engineName() + ": faculty double-booked at " + window);
            assertTrue(roomKeys.add(e.getClassroom().getId() + "_" + window),
                engineName() + ": room double-booked at " + window);
        }
    }

    // ── fixtures (temporary, isolated records only) ─────────────────────────

    private Department newDepartment(String prefix) {
        int n = COUNTER.getAndIncrement();
        Department dept = Department.builder()
            .name(prefix + "-" + n)
            .hodName("Distribution HOD")
            .contactEmail(prefix.toLowerCase() + n + "@local.test")
            .contactPhone("000")
            .building("Distribution Block")
            .isArchived(false)
            .build();
        AcademicYear year = AcademicYear.builder().yearLabel("1st Year").isEnabled(true).build();
        year.addSection(Section.builder().name("A").studentStrength(40).status("ACTIVE").build());
        dept.addAcademicYear(year);
        return departmentRepository.saveAndFlush(dept);
    }

    private Faculty newFaculty(Department dept, String employeeId) {
        int n = COUNTER.getAndIncrement();
        return facultyRepository.saveAndFlush(Faculty.builder()
            .employeeId(employeeId + n)
            .firstName("Dis")
            .lastName(employeeId + n)
            .email(employeeId.toLowerCase() + n + "@local.test")
            .department(dept)
            .designation("Professor")
            .status("AVAILABLE")
            .build());
    }

    private Subject newSubject(Department dept, Section section, Faculty faculty,
            String code, String name, String type, int theory, int practical, Integer blockSize) {
        int n = COUNTER.getAndIncrement();
        return subjectRepository.saveAndFlush(Subject.builder()
            .subjectCode(code + n)
            .subjectName(name)
            .department(dept)
            .academicYear(section.getAcademicYear())
            .section(section)
            .assignedFaculty(faculty)
            .semester(1)
            .credits(4)
            .theoryHours(theory)
            .practicalHours(practical)
            .subjectType(type)
            .sessionBlockSize(blockSize)
            .isActive(true)
            .build());
    }

    private Timetable generateForSection(Department dept, Section section) {
        Timetable timetable = timetableRepository.findBySectionIdAndSemester(section.getId(), 1)
            .orElseGet(() -> Timetable.builder()
                .academicSession(SESSION)
                .department(dept)
                .section(section)
                .semester(1)
                .status("DRAFT")
                .build());
        if (timetable.getId() != null) {
            timetable.getEntries().clear();
            timetable.getConflicts().clear();
            timetable = timetableRepository.saveAndFlush(timetable);
        }
        engine.generateSchedule(timetable, false);
        return timetableRepository.save(timetable);
    }

    private void cleanup(Department dept, List<Subject> subjects, List<Faculty> faculties) {
        try {
            timetableRepository.findByDepartmentId(dept.getId())
                .forEach(t -> timetableRepository.delete(t));
        } catch (Exception ignored) {
        }
        try {
            if (!subjects.isEmpty()) {
                subjectRepository.deleteAll(subjects);
            }
            if (!faculties.isEmpty()) {
                facultyRepository.deleteAll(faculties);
            }
        } catch (Exception ignored) {
        }
        try {
            departmentRepository.delete(dept);
        } catch (Exception ignored) {
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static List<TimetableEntry> entriesOf(Timetable t, Subject s) {
        return t.getEntries().stream()
            .filter(e -> e.getSubject() != null && e.getSubject().getId().equals(s.getId()))
            .toList();
    }

    private static long theoryOf(Timetable t, Subject s) {
        return entriesOf(t, s).stream().filter(e -> !e.isLab()).count();
    }

    private static long labOf(Timetable t, Subject s) {
        return entriesOf(t, s).stream().filter(TimetableEntry::isLab).count();
    }

    private static long theoryDaysOf(Timetable t, Subject s) {
        return entriesOf(t, s).stream()
            .filter(e -> !e.isLab())
            .map(TimetableEntry::getDayOfWeek)
            .distinct()
            .count();
    }

    private static List<Integer> labRunSizes(Timetable t, Subject s) {
        List<Integer> runs = new ArrayList<>();
        Map<String, List<Integer>> byDay = entriesOf(t, s).stream()
            .filter(TimetableEntry::isLab)
            .collect(Collectors.groupingBy(TimetableEntry::getDayOfWeek,
                Collectors.mapping(e -> e.getTimeSlot().getSlotOrder(), Collectors.toList())));
        for (List<Integer> orders : byDay.values()) {
            List<Integer> sorted = orders.stream().sorted().toList();
            int runStart = 0;
            for (int i = 1; i <= sorted.size(); i++) {
                if (i == sorted.size() || sorted.get(i) != sorted.get(i - 1) + 1) {
                    runs.add(i - runStart);
                    runStart = i;
                }
            }
        }
        return runs;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    private static void printMetric(String line) {
        System.out.println("DIST_PROPERTY_METRIC|" + line);
    }
}
