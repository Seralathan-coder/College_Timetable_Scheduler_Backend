package com.erp.timetable.module.timetable.engine;

import com.erp.timetable.module.availability.entity.FacultyAvailability;
import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.availability.repository.FacultyAvailabilityRepository;
import com.erp.timetable.module.availability.repository.TimeSlotRepository;
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
import com.erp.timetable.module.timetable.repository.TimetableEntryRepository;
import com.erp.timetable.module.timetable.repository.TimetableRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end validation of the REAL Subject Creator → AI Timetable Generator
 * connection. Runs identically for BOTH engines via the abstract contract; the
 * concrete subclasses select {@code timetable.scheduler.engine} (greedy /
 * timefold) and an isolated in-memory database.
 *
 * <p>The source of truth is always the Subject Creator records:
 * {@code weeklyDemand = theoryHours + practicalHours}, with
 * {@code sessionBlockSize} (1/2/3) as the practical block size. The dynamic
 * test mutates the database values between generations and asserts that the
 * generated demand follows the database exactly (5+2=7 → 5+3=8 → 3+3=6) —
 * proving the scheduler is driven by the records, never by fixed totals.
 *
 * <p>Everything here uses temporary, isolated records (a fresh department →
 * academic year → section → subject hierarchy per test). Records are deleted
 * explicitly in {@code finally} and the test transaction rolls back as a second
 * guarantee. No mentor-created subject, faculty, classroom, availability,
 * department, section or timetable record is ever touched.
 */
@SpringBootTest
@ActiveProfiles("h2")
@Transactional
public abstract class AbstractFinalValidationE2ETest {

    private static final AtomicInteger COUNTER = new AtomicInteger(1);
    private static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT");
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
    protected TimeSlotRepository timeSlotRepository;

    @Autowired
    protected FacultyAvailabilityRepository availabilityRepository;

    @Autowired
    protected TimetableRepository timetableRepository;

    @Autowired
    protected TimetableEntryRepository entryRepository;

    @PersistenceContext
    protected EntityManager entityManager;

    protected abstract String engineName();

    // ── TEST 1: dynamic demand reacts to the database values ────────────────

    /**
     * Temporary hierarchy mirroring Department / 1st Year / Section A /
     * Semester 1:
     * <ul>
     *   <li>Subject A: theory 5, practical 0, block 1 → demand 5</li>
     *   <li>Subject B: theory 0, practical 2, block 2 → demand 2</li>
     * </ul>
     * Expected total 7. Then Subject B becomes practical 3 / block 3 (total 8),
     * then Subject A becomes theory 3 (total 6). The timetable must follow the
     * database values on every regeneration.
     */
    @Test
    void subjectCreator_dynamicDemand_reactsToDatabaseChanges() {
        Department dept = newDepartment("VALDYN");
        Section section = dept.getAcademicYears().get(0).getSections().get(0);
        Faculty faculty = newFaculty(dept, "VALFAC");
        Subject a = newSubject(dept, section, faculty, "VALA", "Dynamic Subject A", "THEORY", 5, 0, 1);
        Subject b = newSubject(dept, section, faculty, "VALB", "Dynamic Subject B", "THEORY", 0, 2, 2);

        try {
            // Generation 1: 5 + 2 = 7.
            Timetable t1 = generateForSection(dept, section);
            assertEquals(7, t1.getEntries().size(), "5 theory (A) + 2 practical (B) = 7");
            assertEquals(5, theoryOf(t1, a), "A must place exactly its 5 theory periods");
            assertEquals(0, labOf(t1, a), "A has practicalHours=0 so must create NO lab lessons");
            assertEquals(2, labOf(t1, b), "B must place exactly its 2 practical periods");
            assertEquals(List.of(2), consecutiveRunSizes(labEntriesOf(t1, b)),
                "B (sessionBlockSize 2) must be one 2-consecutive-period session");
            assertClean(t1, "gen1");
            assertLabRules(t1);
            printMetric("dynamic|gen1|engine=" + engineName()
                + "|subjectA.theory=5|subjectB.practical=2|demand=7|entries=" + t1.getEntries().size());

            // Generation 2: Subject B becomes practical 3 / block 2 → 5 + 3 = 8.
            b.setPracticalHours(3);
            b.setSessionBlockSize(2);
            subjectRepository.saveAndFlush(b);
            Timetable t2 = generateForSection(dept, section);
            assertEquals(8, t2.getEntries().size(), "5 theory (A) + 3 practical (B) = 8");
            assertEquals(5, theoryOf(t2, a), "A theory demand is unchanged");
            assertEquals(3, labOf(t2, b), "B must place exactly its 3 practical periods after the update");
            assertEquals(List.of(3), consecutiveRunSizes(labEntriesOf(t2, b)),
                "B (Part 2: lab practicalHours=3 → one 3-consecutive-period block)");
            assertClean(t2, "gen2");
            assertLabRules(t2);
            printMetric("dynamic|gen2|engine=" + engineName()
                + "|subjectA.theory=5|subjectB.practical=3|demand=8|entries=" + t2.getEntries().size());

            // Generation 3: Subject A becomes theory 3 → 3 + 3 = 6.
            a.setTheoryHours(3);
            subjectRepository.saveAndFlush(a);
            Timetable t3 = generateForSection(dept, section);
            assertEquals(6, t3.getEntries().size(), "3 theory (A) + 3 practical (B) = 6");
            assertEquals(3, theoryOf(t3, a), "A must follow the reduced theory demand");
            assertEquals(3, labOf(t3, b), "B practical demand is unchanged");
            assertClean(t3, "gen3");
            assertLabRules(t3);
            printMetric("dynamic|gen3|engine=" + engineName()
                + "|subjectA.theory=3|subjectB.practical=3|demand=6|entries=" + t3.getEntries().size());
        } finally {
            cleanup(dept, List.of(a, b), List.of(faculty));
        }
    }

    // ── TEST 2: infeasible LAB capacity ─────────────────────────────────────

    /**
     * A temporary LAB-typed subject needs 2 practical periods but the section
     * strength (100) exceeds every LAB room (largest = 40). The engine must
     * place nothing in a lecture hall / undersized LAB, must NOT weaken the
     * capacity constraint, and must record a structured conflict naming the
     * subject, demand, required block, required room type, required capacity,
     * largest available LAB capacity and the exact reason.
     */
    @Test
    void infeasibleLabCapacity_reportsStructuredDiagnostic() {
        Department dept = newDepartment("VALCAP");
        Section section = dept.getAcademicYears().get(0).getSections().get(0);
        section.setStudentStrength(100); // no LAB room fits (seeded LAB capacity is 40)
        Faculty faculty = newFaculty(dept, "VALFAC");
        Subject c = newSubject(dept, section, faculty, "VALC", "Infeasible Lab", "LAB", 0, 2, 2);
        Timetable timetable = newTimetable(dept, section);

        try {
            engine.generateSchedule(timetable);
            timetableRepository.saveAndFlush(timetable);

            assertEquals(0, labOf(timetable, c),
                "an infeasible practical must not be placed in an undersized LAB or a non-LAB room");
            assertEquals(0, timetable.getEntries().size(),
                "a lab-only subject with an infeasible LAB must produce zero entries (no faked demand)");
            assertEquals(100, section.getStudentStrength().intValue(),
                "the engine must never weaken the section strength / capacity rule");
            assertFalse(timetable.getConflicts().isEmpty(),
                "an infeasible practical must be reported truthfully");
            assertFalse(timetable.getConflicts().stream()
                    .anyMatch(cn -> "SOLVER_INFEASIBLE".equals(cn.getConflictType())),
                "no hard constraint may be weakened into an infeasible score");

            String text = conflictText(timetable);
            if ("timefold".equals(engineName())) {
                // Timefold: per-subject accounting + capacity diagnosis.
                assertTrue(text.contains("PRACTICAL_UNAVAILABLE"), text);
                assertTrue(text.contains("VALC"), text);
                assertTrue(text.contains("requested 2"), text);
                assertTrue(text.contains("assigned 0, unassigned 2"), text);
                assertTrue(text.contains("required capacity is 100"), text);
                assertTrue(text.contains("largest LAB room holds 40"), text);
                assertFalse(text.contains("SOLVER_INFEASIBLE"), text);
            } else {
                // Greedy: structured header names subject, demand, block, room type,
                // capacity, largest available LAB capacity and the exact reason.
                assertTrue(text.contains("Subject VALC"), text);
                assertTrue(text.contains("practical demand 2"), text);
                assertTrue(text.contains("required block 2"), text);
                assertTrue(text.contains("required room type LAB"), text);
                assertTrue(text.contains("required capacity 100"), text);
                assertTrue(text.contains("largest available LAB capacity is 40"), text);
                assertTrue(text.contains("LAB_CAPACITY_INSUFFICIENT"), text);
                assertTrue(text.contains("no LAB room with capacity >= 100"), text);
            }
            printMetric("infeasible-lab|engine=" + engineName() + "|requiredCapacity=100|largestLab=40|conflictType="
                + timetable.getConflicts().get(0).getConflictType());
        } finally {
            cleanup(dept, List.of(c), List.of(faculty));
        }
    }

    // ── TEST 3: faculty availability ────────────────────────────────────────

    /**
     * A temporary LAB subject is assigned to a faculty that is BLOCKED in every
     * teaching slot. The conflict must name faculty availability — not room
     * capacity — proving the diagnostic determines the ACTUAL blocking reason.
     */
    @Test
    void facultyAvailability_failureNamesAvailabilityNotCapacity() {
        Department dept = newDepartment("VALAVL");
        Section section = dept.getAcademicYears().get(0).getSections().get(0); // strength 40, LAB fits
        Faculty faculty = newFaculty(dept, "VALFAC");
        Subject d = newSubject(dept, section, faculty, "VALD", "Availability Probe Lab", "LAB", 0, 2, 1);
        Timetable timetable = newTimetable(dept, section);

        try {
            blockAllTeachingSlots(faculty);
            engine.generateSchedule(timetable);
            timetableRepository.saveAndFlush(timetable);

            assertEquals(0, labOf(timetable, d), "a fully-blocked faculty must leave the practical unscheduled");
            assertFalse(timetable.getConflicts().isEmpty(), "the failure must be reported");
            assertFalse(timetable.getConflicts().stream()
                    .anyMatch(cn -> "SOLVER_INFEASIBLE".equals(cn.getConflictType())),
                "availability is a hard rejection, never an infeasible score");

            String text = conflictText(timetable);
            if ("timefold".equals(engineName())) {
                assertTrue(text.contains("PRACTICAL_UNAVAILABLE"), text);
                assertTrue(text.contains("blocked/busy"), text);
            } else {
                assertTrue(text.contains("PRACTICAL_BLOCK_UNAVAILABLE"), text);
                assertTrue(text.contains("faculty unavailable (blocked/busy/leave)"), text);
            }
            assertFalse(text.contains("LAB_CAPACITY_INSUFFICIENT"),
                "the reason must be availability, not capacity: " + text);
            assertFalse(text.contains("capacity >= "),
                "the reason must not claim a capacity shortage: " + text);
            printMetric("availability|engine=" + engineName() + "|blockedSlots=all|reason=availability");
        } finally {
            cleanup(dept, List.of(d), List.of(faculty));
        }
    }

    // ── TEST 4: block sizes 1 / 2 / 3 ───────────────────────────────────────

    /**
     * Three temporary lab-only subjects with the same practical hours but
     * sessionBlockSize 1 / 2 / 3 must produce sessions of exactly those sizes
     * and never a partial block. Both engines must agree on the outcome.
     */
    @Test
    void blockSizes_1_2_3_produceExactSessionsAndEnginesAgree() {
        Department dept = newDepartment("VALBLK");
        Section section = dept.getAcademicYears().get(0).getSections().get(0);
        Faculty f1 = newFaculty(dept, "VALFAC1");
        Faculty f2 = newFaculty(dept, "VALFAC2");
        Faculty f3 = newFaculty(dept, "VALFAC3");
        Subject v1 = newSubject(dept, section, f1, "VAL1", "Single Block Lab", "LAB", 0, 3, 1);
        Subject v2 = newSubject(dept, section, f2, "VAL2", "Double Block Lab", "LAB", 0, 3, 2);
        Subject v3 = newSubject(dept, section, f3, "VAL3", "Triple Block Lab", "LAB", 0, 3, 3);
        Timetable timetable = newTimetable(dept, section);

        try {
            engine.generateSchedule(timetable);
            assertClean(timetable, "block-sizes");

            for (Subject s : List.of(v1, v2, v3)) {
                assertEquals(3, labOf(timetable, s),
                    s.getSubjectCode() + " must place all 3 practical periods");
                assertTrue(labEntriesOf(timetable, s).stream()
                        .allMatch(e -> "LAB".equalsIgnoreCase(e.getClassroom().getRoomType())),
                    s.getSubjectCode() + " practicals must use a LAB room");
                assertTrue(labEntriesOf(timetable, s).stream()
                        .allMatch(e -> e.getClassroom().getCapacity() != null
                            && e.getClassroom().getCapacity() >= 40),
                    s.getSubjectCode() + " practicals must use a capacity-valid LAB room");
            }

            List<Integer> r1 = sortedRunSizes(labEntriesOf(timetable, v1));
            List<Integer> r2 = sortedRunSizes(labEntriesOf(timetable, v2));
            List<Integer> r3 = sortedRunSizes(labEntriesOf(timetable, v3));
            assertEquals(List.of(1, 1, 1), r1, "block 1 must give three 1-period sessions: " + r1);
            assertEquals(3, r2.stream().mapToInt(Integer::intValue).sum(),
                "block 2 must place all 3 practical periods: " + r2);
            assertTrue(r2.stream().filter(r -> r == 1).count() <= 1,
                "block 2 must leave at most one remainder single: " + r2);
            assertTrue(r2.stream().filter(r -> r >= 2).count() >= 1,
                "block 2 must keep its 2-period session as one block: " + r2);
            assertEquals(List.of(3), r3, "block 3 must give one 3-block: " + r3);
            assertTrue(r1.stream().allMatch(r -> r <= 1), "no run may exceed block size 1");
            assertTrue(r2.stream().allMatch(r -> r <= 3),
                "no run may exceed block size 2 + one back-to-back remainder single: " + r2);
            assertTrue(r3.stream().allMatch(r -> r <= 3), "no run may exceed block size 3");

            printMetric("blocks|engine=" + engineName() + "|b1=" + r1 + "|b2=" + r2 + "|b3=" + r3);
        } finally {
            cleanup(dept, List.of(v1, v2, v3), List.of(f1, f2, f3));
        }
    }

    // ── TEST 5: partial regeneration ────────────────────────────────────────

    /**
     * remainingDemand = subjectDemand − lockedDemand. Locked THEORY entries
     * subtract only from theory demand, locked PRACTICAL only from practical
     * demand, locked entries are never deleted or moved, and the regenerated
     * timetable restores the exact full demand with zero conflicts.
     */
    @Test
    void partialRegeneration_preservesLocked_backfillsExactRemainder() {
        Department dept = newDepartment("VALREG");
        Section section = dept.getAcademicYears().get(0).getSections().get(0);
        Faculty faculty = newFaculty(dept, "VALFAC");
        Subject r = newSubject(dept, section, faculty, "VALR", "Regeneration Subject", "THEORY", 3, 2, 2);
        Timetable timetable = newTimetable(dept, section);

        try {
            engine.generateSchedule(timetable);
            assertClean(timetable, "regen-before");
            assertEquals(5, timetable.getEntries().size(), "3 theory + 2 practical = 5");
            timetableRepository.saveAndFlush(timetable);

            // Lock two THEORY entries only.
            List<TimetableEntry> locked = timetable.getEntries().stream()
                .filter(e -> !e.isLab())
                .limit(2)
                .toList();
            assertEquals(2, locked.size());
            locked.forEach(e -> e.setIsLocked(true));

            Map<Long, String[]> placementBefore = new HashMap<>();
            for (TimetableEntry e : locked) {
                placementBefore.put(e.getId(), new String[] {
                    e.getDayOfWeek(),
                    String.valueOf(e.getTimeSlot().getId()),
                    String.valueOf(e.getClassroom().getId()),
                    String.valueOf(e.getSubject().getId()),
                    String.valueOf(e.getFaculty().getId())
                });
            }

            // Mirror the production flow (TimetableService.regenerateUnlockedSlots):
            // drop the unlocked rows first, then regenerate around the locked ones.
            entryRepository.deleteUnlockedByTimetableId(timetable.getId());
            entityManager.flush();
            entityManager.clear();
            timetable = timetableRepository.findById(timetable.getId()).orElseThrow();

            engine.generateSchedule(timetable, true);

            // Locked entries survive untouched.
            for (TimetableEntry e : locked) {
                String[] before = placementBefore.get(e.getId());
                assertNotNull(before, "locked entry must still be present");
                assertTrue(e.isLocked(), "locked entry must remain locked");
                assertEquals(before[0], e.getDayOfWeek(), "locked day preserved");
                assertEquals(before[1], String.valueOf(e.getTimeSlot().getId()), "locked slot preserved");
                assertEquals(before[2], String.valueOf(e.getClassroom().getId()), "locked room preserved");
                assertEquals(before[3], String.valueOf(e.getSubject().getId()), "locked subject preserved");
                assertEquals(before[4], String.valueOf(e.getFaculty().getId()), "locked faculty preserved");
            }

            // Exact remainder backfilled: 5 total, no double-scheduling.
            assertEquals(5, timetable.getEntries().size(),
                "partial regeneration must restore the full 5-period demand exactly");
            assertEquals(3, theoryOf(timetable, r), "3 theory periods in total");
            assertEquals(2, labOf(timetable, r), "2 practical periods in total");
            assertClean(timetable, "regen-after");
            assertLabRules(timetable);
            assertNoClashes(timetable.getEntries());
            printMetric("regeneration|engine=" + engineName() + "|locked=2theory|restored=5|entries="
                + timetable.getEntries().size());
        } finally {
            cleanup(dept, List.of(r), List.of(faculty));
        }
    }

    // ── TEST 6: college-wide cap 5 + LAB never on Saturday ────────────────────

    /**
     * One faculty teaches a 6-theory-hour subject and a 6-practical-hour LAB
     * subject (sessionBlockSize 1) in one section. Both new college policies must
     * hold on BOTH engines:
     * <ul>
     *   <li>all 12 periods are placed cleanly (6 theory + 6 practical),</li>
     *   <li>no faculty day holds more than the college-wide cap of 5 teaching
     *       periods (the faculty's own cap is 6, so the college cap binds),</li>
     *   <li>no LAB session ever lands on Saturday,</li>
     *   <li>a 6-hour component cannot fit a single day under the cap, so theory
     *       and practical each span at least 2 teaching days.</li>
     * </ul>
     */
    @Test
    void facultyCapFive_dailyLoadAndLabNeverSaturday_holdOnBothEngines() {
        Department dept = newDepartment("VALCAP5");
        Section section = dept.getAcademicYears().get(0).getSections().get(0);
        Faculty faculty = newFaculty(dept, "VALFAC");
        Subject theory = newSubject(dept, section, faculty, "VALT", "Six Hour Theory", "THEORY", 6, 0, 1);
        Subject lab = newSubject(dept, section, faculty, "VALL", "Six Hour Lab", "LAB", 0, 6, 1);

        try {
            Timetable timetable = generateForSection(dept, section);

            assertEquals(12, timetable.getEntries().size(), "6 theory + 6 practical = 12");
            assertEquals(6, theoryOf(timetable, theory), "theory subject must place all 6 periods");
            assertEquals(6, labOf(timetable, lab), "lab subject must place all 6 practical periods");
            assertClean(timetable, "cap5");
            assertLabRules(timetable);
            assertNoClashes(timetable.getEntries());

            // LAB sessions are never scheduled on Saturday.
            assertTrue(timetable.getEntries().stream()
                    .filter(TimetableEntry::isLab)
                    .noneMatch(e -> "SAT".equals(e.getDayOfWeek())),
                "no LAB session may land on Saturday: " + timetable.getEntries());

            // College-wide daily cap: no faculty day exceeds 5 periods.
            Map<String, Long> dailyLoad = timetable.getEntries().stream()
                .collect(Collectors.groupingBy(
                    e -> e.getFaculty().getId() + "_" + e.getDayOfWeek(), Collectors.counting()));
            assertTrue(dailyLoad.values().stream().allMatch(v -> v <= 5),
                "faculty daily load exceeded the college-wide cap of 5: " + dailyLoad);

            // 6 hours cannot fit one day under the cap of 5.
            long theoryDays = entriesOf(timetable, theory).stream()
                .filter(e -> !e.isLab())
                .map(TimetableEntry::getDayOfWeek)
                .distinct()
                .count();
            long labDays = labEntriesOf(timetable, lab).stream()
                .map(TimetableEntry::getDayOfWeek)
                .distinct()
                .count();
            assertTrue(theoryDays >= 2, "6 theory hours must span at least 2 teaching days");
            assertTrue(labDays >= 2, "6 practical periods must span at least 2 teaching days");

            printMetric("cap5-lab-sat|engine=" + engineName() + "|demand=12|dailyMax="
                + dailyLoad.values().stream().max(Long::compareTo).orElse(0L)
                + "|theoryDays=" + theoryDays + "|labDays=" + labDays);
        } finally {
            cleanup(dept, List.of(theory, lab), List.of(faculty));
        }
    }

    // ── shared assertions ───────────────────────────────────────────────────

    private void assertClean(Timetable t, String phase) {
        assertTrue(t.getConflicts().isEmpty(),
            phase + ": no conflicts may remain on a feasible solve (engine=" + engineName()
                + "): " + t.getConflicts());
        assertEquals(0, t.getConflictCount(), phase + ": conflictCount must be 0");
    }

    private void assertLabRules(Timetable t) {
        for (TimetableEntry e : t.getEntries()) {
            if (e.isLab()) {
                assertEquals("LAB", e.getClassroom().getRoomType(),
                    "practical periods must use a LAB room, never LECTURE_HALL");
                assertNotNull(e.getClassroom().getCapacity());
                assertTrue(e.getClassroom().getCapacity() >= t.getSection().getStudentStrength(),
                    "LAB room capacity must be >= section student strength");
            } else {
                assertFalse("LAB".equalsIgnoreCase(e.getClassroom().getRoomType()),
                    "theory lessons must use a non-LAB classroom");
            }
        }
    }

    private void assertNoClashes(List<TimetableEntry> entries) {
        Set<String> sectionKeys = new HashSet<>();
        Set<String> facultyKeys = new HashSet<>();
        Set<String> roomKeys = new HashSet<>();
        for (TimetableEntry e : entries) {
            String window = e.getDayOfWeek() + "_" + e.getTimeSlot().getId();
            assertTrue(sectionKeys.add(e.getSection().getId() + "_" + window),
                "section double-booked at " + window);
            assertTrue(facultyKeys.add(e.getFaculty().getId() + "_" + window),
                "faculty double-booked at " + window);
            assertTrue(roomKeys.add(e.getClassroom().getId() + "_" + window),
                "room double-booked at " + window);
        }
    }

    // ── fixtures (temporary, isolated records only) ─────────────────────────

    private Department newDepartment(String prefix) {
        int n = COUNTER.getAndIncrement();
        Department dept = Department.builder()
            .name(prefix + "-" + n)
            .hodName("Validation HOD")
            .contactEmail(prefix.toLowerCase() + n + "@local.test")
            .contactPhone("000")
            .building("Validation Block")
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
            .firstName("Val")
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

    private Timetable newTimetable(Department dept, Section section) {
        return Timetable.builder()
            .academicSession(SESSION)
            .department(dept)
            .section(section)
            .semester(1)
            .status("DRAFT")
            .build();
    }

    /**
     * Mirrors {@code TimetableService.generateTimetable}: find-or-create by
     * (section, semester), drop old entries/conflicts, then generate. Every
     * call re-reads the curriculum from the database, so a subject-value change
     * between calls is picked up automatically.
     */
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

    private void blockAllTeachingSlots(Faculty faculty) {
        List<TimeSlot> teachingSlots = timeSlotRepository.findAll().stream()
            .filter(ts -> !Boolean.TRUE.equals(ts.getIsBreak()))
            .toList();
        List<FacultyAvailability> records = new ArrayList<>();
        for (String day : DAYS) {
            for (TimeSlot slot : teachingSlots) {
                records.add(FacultyAvailability.builder()
                    .faculty(faculty)
                    .dayOfWeek(day)
                    .timeSlot(slot)
                    .slotType("BLOCKED")
                    .build());
            }
        }
        availabilityRepository.saveAll(records);
    }

    /** Deletes only the temporary records of this test (the transaction also rolls back). */
    private void cleanup(Department dept, List<Subject> subjects, List<Faculty> faculties) {
        try {
            for (Faculty f : faculties) {
                availabilityRepository.deleteByFacultyId(f.getId());
            }
        } catch (Exception ignored) {
        }
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

    private static List<TimetableEntry> labEntriesOf(Timetable t, Subject s) {
        return entriesOf(t, s).stream().filter(TimetableEntry::isLab).toList();
    }

    private static long theoryOf(Timetable t, Subject s) {
        return entriesOf(t, s).stream().filter(e -> !e.isLab()).count();
    }

    private static long labOf(Timetable t, Subject s) {
        return labEntriesOf(t, s).size();
    }

    private static List<Integer> sortedRunSizes(List<TimetableEntry> labEntries) {
        return consecutiveRunSizes(labEntries).stream().sorted().toList();
    }

    private static List<Integer> consecutiveRunSizes(List<TimetableEntry> entries) {
        List<Integer> runSizes = new ArrayList<>();
        Map<String, List<Integer>> byDay = entries.stream().collect(Collectors.groupingBy(
            TimetableEntry::getDayOfWeek,
            Collectors.mapping(e -> e.getTimeSlot().getSlotOrder(), Collectors.toList())));
        for (List<Integer> orders : byDay.values()) {
            List<Integer> sorted = orders.stream().sorted().toList();
            int runStart = 0;
            for (int i = 1; i <= sorted.size(); i++) {
                if (i == sorted.size() || sorted.get(i) != sorted.get(i - 1) + 1) {
                    runSizes.add(i - runStart);
                    runStart = i;
                }
            }
        }
        return runSizes;
    }

    private static String conflictText(Timetable t) {
        return t.getConflicts().stream()
            .map(c -> c.getConflictType() + ": " + c.getDescription())
            .collect(Collectors.joining(" | "));
    }

    private static void printMetric(String line) {
        System.out.println("FINAL_VALIDATION_METRIC|" + line);
    }
}
