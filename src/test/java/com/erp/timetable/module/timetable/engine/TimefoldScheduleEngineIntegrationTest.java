package com.erp.timetable.module.timetable.engine;

import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.erp.timetable.module.timetable.engine.shared.CurriculumDataLoader;
import com.erp.timetable.module.timetable.engine.shared.SubjectDemandService;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import com.erp.timetable.module.timetable.repository.TimetableEntryRepository;
import com.erp.timetable.module.timetable.repository.TimetableRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 — Timefold scheduling engine integration test.
 *
 * <p>Boots the full Spring context on H2 with {@code timetable.scheduler.engine=timefold}
 * so the {@link TimefoldScheduleEngine} is the only {@link ScheduleEngine} registered,
 * then exercises it end-to-end on the seeded master data (CSE section A, semester 3):
 * a full regeneration must produce a feasible, conflict-free timetable matching the
 * curriculum demand, and a partial regeneration must preserve every locked entry while
 * re-scheduling only the unlocked ones.
 *
 * <p>The section strength is lowered to 40 so the seeded LAB room (capacity 40) passes
 * the solver's room-capacity hard constraint.
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = "timetable.scheduler.engine=timefold")
@Transactional
class TimefoldScheduleEngineIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ScheduleEngine engine;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private FacultyRepository facultyRepository;

    @Autowired
    private CurriculumDataLoader curriculumDataLoader;

    @Autowired
    private SubjectDemandService subjectDemandService;

    @Autowired
    private TimetableRepository timetableRepository;

    @Autowired
    private TimetableEntryRepository entryRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void timefoldEngine_isTheOnlyRegisteredScheduleEngine() {
        assertEquals(1, applicationContext.getBeansOfType(ScheduleEngine.class).size(),
            "under engine=timefold only the Timefold engine may be registered");
        assertInstanceOf(TimefoldScheduleEngine.class, engine,
            "ScheduleEngine bean must be the Timefold implementation");
    }

    @Test
    void generateSchedule_buildsFeasibleConflictFreeTimetable() {
        Timetable timetable = buildTimetable();

        engine.generateSchedule(timetable);

        assertEquals("GENERATED", timetable.getStatus());
        assertTrue(timetable.getConflicts().isEmpty(),
            "no conflicts may remain after a feasible solve: " + timetable.getConflicts());
        assertEquals(0, timetable.getConflictCount());

        List<TimetableEntry> entries = timetable.getEntries();
        assertEquals(expectedDemand(timetable), entries.size(),
            "every curriculum lesson must be scheduled: " + entries.size());
        assertFalse(entries.isEmpty());

        // 1. No double-booked faculty / room / section at any (day, slot).
        Set<String> facultyKeys = new HashSet<>();
        Set<String> roomKeys = new HashSet<>();
        Set<String> sectionKeys = new HashSet<>();
        for (TimetableEntry e : entries) {
            String window = e.getDayOfWeek() + "_" + e.getTimeSlot().getId();
            assertTrue(facultyKeys.add(e.getFaculty().getId() + "_" + window),
                "faculty double-booked at " + window);
            assertTrue(roomKeys.add(e.getClassroom().getId() + "_" + window),
                "room double-booked at " + window);
            assertTrue(sectionKeys.add(e.getSection().getId() + "_" + window),
                "section double-booked at " + window);
        }

        // 2. Lab (CS205L: 3 practical hours) carries the DB-default sessionBlockSize
        //    of 1, which is honored verbatim: the solver places each practical hour
        //    as a separate single-period session in one LAB room (a run of 2+ would
        //    exceed the configured block size).
        List<TimetableEntry> labEntries = entries.stream().filter(TimetableEntry::getIsLab).toList();
        assertEquals(3, labEntries.size(), "the LAB subject must be placed as exactly 3 practical periods");
        assertTrue(labEntries.stream().allMatch(e -> "LAB".equalsIgnoreCase(e.getClassroom().getRoomType())),
            "practical periods must use a LAB room");
        assertEquals(1, labEntries.stream().map(e -> e.getClassroom().getId()).collect(Collectors.toSet()).size(),
            "practical periods must use a single LAB room");

        List<Integer> runSizes = new ArrayList<>();
        Map<String, List<Integer>> byDay = labEntries.stream().collect(Collectors.groupingBy(
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
        assertEquals(List.of(1, 1, 1), runSizes,
            "3 practical hours with sessionBlockSize 1 must form three 1-period sessions: " + runSizes);

        // 3. Each theory subject receives its weekly coverage (one lesson per period).
        Map<Long, Long> bySubject = entries.stream()
            .filter(e -> !Boolean.TRUE.equals(e.getIsLab()))
            .collect(Collectors.groupingBy(e -> e.getSubject().getId(), Collectors.counting()));
        for (Subject s : curriculumDataLoader.loadSubjectsForTimetable(timetable)) {
            if ("LAB".equalsIgnoreCase(s.getSubjectType())) continue;
            long expected = Math.max(1, s.getTheoryHours());
            assertEquals(expected, bySubject.getOrDefault(s.getId(), 0L),
                "weekly coverage mismatch for " + s.getSubjectCode());
        }

        // 4. Optimization score is a sane percentage.
        assertNotNull(timetable.getOptimizationScore());
        assertTrue(timetable.getOptimizationScore() >= 0 && timetable.getOptimizationScore() <= 100,
            "optimization score out of range: " + timetable.getOptimizationScore());
    }

    @Test
    void generateSchedule_labWithSessionBlockSizeThree_placesSingleThreePeriodBlock() {
        Timetable timetable = buildTimetable();
        Faculty faculty = facultyRepository.findByEmployeeId("FAC001").orElseThrow();
        Subject lab = subjectRepository.save(Subject.builder()
            .subjectCode("CSBLK03")
            .subjectName("Triple Block Lab")
            .department(timetable.getDepartment())
            .academicYear(timetable.getSection().getAcademicYear())
            .section(timetable.getSection())
            .assignedFaculty(faculty)
            .semester(3)
            .credits(2)
            .theoryHours(0)
            .practicalHours(3)
            .subjectType("LAB")
            .sessionBlockSize(3)
            .isActive(true)
            .build());

        engine.generateSchedule(timetable);

        // Per-subject block size 3: the solver must group the 3 practical lessons
        // into a single 3-consecutive-period block (any split is a hard violation
        // and leaving lessons unassigned is soft-dominated).
        List<TimetableEntry> labEntries = timetable.getEntries().stream()
            .filter(e -> e.getSubject() != null && e.getSubject().getId().equals(lab.getId()))
            .toList();
        assertEquals(3, labEntries.size(),
            "3 practical hours with per-subject block 3 must be placed as 3 periods: " + labEntries);
        assertTrue(labEntries.stream().allMatch(e -> "LAB".equalsIgnoreCase(e.getClassroom().getRoomType())),
            "practical periods must use a LAB room");
        assertEquals(1, labEntries.stream().map(e -> e.getClassroom().getId()).collect(Collectors.toSet()).size(),
            "all practical periods must use the same LAB room");

        List<Integer> runSizes = consecutiveRunSizes(labEntries);
        assertEquals(List.of(3), runSizes,
            "per-subject block size 3 must produce a single 3-period consecutive block: " + runSizes);
        assertTrue(timetable.getConflicts().isEmpty(),
            "no conflicts may remain after the solve: " + timetable.getConflicts());
    }

    @Test
    void generateSchedule_sessionBlockSizes_1_2_and3_produceOneTwoThreePeriodSessions() {
        Timetable timetable = buildTimetable();
        Faculty faculty = facultyRepository.findByEmployeeId("FAC001").orElseThrow();

        // Temporary LAB subjects (removed below and rolled back by @Transactional),
        // each with 3 practical hours but a different configured sessionBlockSize:
        //   1 → three 1-period sessions      ([1, 1, 1])
        //   2 → one 2-period block + single, which may merge back-to-back ([2, 1] or [3])
        //   3 → one 3-period block           ([3])
        // The configured value is the source of truth — the global
        // practical-block-size fallback must never replace an explicit 1.
        Subject blk1 = subjectRepository.save(Subject.builder()
            .subjectCode("CSBLK1")
            .subjectName("Single Block Lab")
            .department(timetable.getDepartment())
            .academicYear(timetable.getSection().getAcademicYear())
            .section(timetable.getSection())
            .assignedFaculty(faculty)
            .semester(3)
            .credits(2)
            .theoryHours(0)
            .practicalHours(3)
            .subjectType("LAB")
            .sessionBlockSize(1)
            .isActive(true)
            .build());
        Subject blk2 = subjectRepository.save(Subject.builder()
            .subjectCode("CSBLK2")
            .subjectName("Double Block Lab")
            .department(timetable.getDepartment())
            .academicYear(timetable.getSection().getAcademicYear())
            .section(timetable.getSection())
            .assignedFaculty(faculty)
            .semester(3)
            .credits(2)
            .theoryHours(0)
            .practicalHours(3)
            .subjectType("LAB")
            .sessionBlockSize(2)
            .isActive(true)
            .build());
        Subject blk3 = subjectRepository.save(Subject.builder()
            .subjectCode("CSBLK3")
            .subjectName("Triple Block Lab")
            .department(timetable.getDepartment())
            .academicYear(timetable.getSection().getAcademicYear())
            .section(timetable.getSection())
            .assignedFaculty(faculty)
            .semester(3)
            .credits(2)
            .theoryHours(0)
            .practicalHours(3)
            .subjectType("LAB")
            .sessionBlockSize(3)
            .isActive(true)
            .build());

        try {
            engine.generateSchedule(timetable);

            for (Subject subject : List.of(blk1, blk2, blk3)) {
                List<TimetableEntry> labEntries = timetable.getEntries().stream()
                    .filter(e -> e.getSubject() != null && e.getSubject().getId().equals(subject.getId()))
                    .toList();
                assertEquals(3, labEntries.size(),
                    subject.getSubjectCode() + " must place all 3 practical periods: " + labEntries);
                assertTrue(labEntries.stream().allMatch(e -> "LAB".equalsIgnoreCase(e.getClassroom().getRoomType())),
                    subject.getSubjectCode() + " practical periods must use a LAB room");
            }

            assertEquals(List.of(3), consecutiveRunSizes(entriesOf(timetable, blk1)).stream().sorted().toList(),
                "Part 2: lab practicalHours=3 always produces one 3-period block (dropdown ignored): "
                    + consecutiveRunSizes(entriesOf(timetable, blk1)));
            assertEquals(List.of(3), consecutiveRunSizes(entriesOf(timetable, blk2)).stream().sorted().toList(),
                "Part 2: lab practicalHours=3 always produces one 3-period block (dropdown ignored): "
                    + consecutiveRunSizes(entriesOf(timetable, blk2)));
            assertEquals(List.of(3), consecutiveRunSizes(entriesOf(timetable, blk3)).stream().sorted().toList(),
                "Part 2: lab practicalHours=3 always produces one 3-period block: "
                    + consecutiveRunSizes(entriesOf(timetable, blk3)));
            assertTrue(timetable.getConflicts().isEmpty(),
                "no conflicts may remain after the solve: " + timetable.getConflicts());
        } finally {
            subjectRepository.delete(blk3);
            subjectRepository.delete(blk2);
            subjectRepository.delete(blk1);
            subjectRepository.flush();
        }
    }

    private static List<TimetableEntry> entriesOf(Timetable timetable, Subject subject) {
        return timetable.getEntries().stream()
            .filter(e -> e.getSubject() != null && e.getSubject().getId().equals(subject.getId()))
            .toList();
    }

    @Test
    void generateSchedule_dynamicDemand_subjectsXAndY_placeNineLessons() {
        Timetable timetable = buildTimetable();
        Faculty faculty = facultyRepository.findByEmployeeId("FAC001").orElseThrow();

        // Dynamic-demand dataset (temporary, isolated records — removed below and
        // additionally rolled back by @Transactional):
        //   Subject X: 3 theory + 2 practical hours, per-subject block 2.
        //   Subject Y: 4 theory hours, default block (single periods).
        Subject subjectX = subjectRepository.save(Subject.builder()
            .subjectCode("TMPX")
            .subjectName("Dynamic Subject X")
            .department(timetable.getDepartment())
            .academicYear(timetable.getSection().getAcademicYear())
            .section(timetable.getSection())
            .assignedFaculty(faculty)
            .semester(3)
            .credits(4)
            .theoryHours(3)
            .practicalHours(2)
            .subjectType("THEORY")
            .sessionBlockSize(2)
            .isActive(true)
            .build());
        Subject subjectY = subjectRepository.save(Subject.builder()
            .subjectCode("TMPY")
            .subjectName("Dynamic Subject Y")
            .department(timetable.getDepartment())
            .academicYear(timetable.getSection().getAcademicYear())
            .section(timetable.getSection())
            .assignedFaculty(faculty)
            .semester(3)
            .credits(4)
            .theoryHours(4)
            .practicalHours(0)
            .subjectType("THEORY")
            .sessionBlockSize(1)
            .isActive(true)
            .build());

        try {
            engine.generateSchedule(timetable);

            // Demand derived purely from the subject records: 3 + 2 + 4 = 9, on top
            // of the seeded 9 lessons, all scheduled with zero conflicts.
            assertEquals(expectedDemand(timetable), timetable.getEntries().size(),
                "every curriculum lesson (seeded + dynamic) must be scheduled: "
                    + timetable.getEntries().size());

            List<TimetableEntry> xTheory = timetable.getEntries().stream()
                .filter(e -> e.getSubject() != null && e.getSubject().getId().equals(subjectX.getId()))
                .filter(e -> !Boolean.TRUE.equals(e.getIsLab()))
                .toList();
            List<TimetableEntry> xPractical = timetable.getEntries().stream()
                .filter(e -> e.getSubject() != null && e.getSubject().getId().equals(subjectX.getId()))
                .filter(TimetableEntry::getIsLab)
                .toList();
            List<TimetableEntry> yEntries = timetable.getEntries().stream()
                .filter(e -> e.getSubject() != null && e.getSubject().getId().equals(subjectY.getId()))
                .toList();

            assertEquals(3, xTheory.size(), "X must place 3 theory periods: " + xTheory);
            assertEquals(2, xPractical.size(), "X must place 2 practical periods: " + xPractical);
            assertTrue(xPractical.stream().allMatch(e -> "LAB".equalsIgnoreCase(e.getClassroom().getRoomType())),
                "X practical periods must use a LAB room");
            assertEquals(4, yEntries.size(), "Y must place 4 theory periods: " + yEntries);
            assertTrue(timetable.getConflicts().isEmpty(),
                "no conflicts may remain after the solve: " + timetable.getConflicts());
        } finally {
            // Temporary records are removed immediately after use (plus the
            // transaction rollback guarantees full isolation).
            subjectRepository.delete(subjectX);
            subjectRepository.delete(subjectY);
            subjectRepository.flush();
        }
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

    @Test
    void generateSchedule_partialRegeneration_preservesLockedEntries() {
        Timetable timetable = buildTimetable();
        engine.generateSchedule(timetable);
        assertTrue(timetable.getConflicts().isEmpty());

        // Persist so entries carry real ids — the partial regeneration matches
        // locked lessons back to their existing rows via sourceEntryId, exactly
        // like the production flow (TimetableService saves between generations).
        timetableRepository.saveAndFlush(timetable);

        // Lock a few theory entries before the partial regeneration.
        List<TimetableEntry> locked = timetable.getEntries().stream()
            .filter(e -> !Boolean.TRUE.equals(e.getIsLab()))
            .limit(3)
            .toList();
        assertFalse(locked.isEmpty());
        locked.forEach(e -> e.setIsLocked(true));

        Map<Long, String[]> placementBefore = new HashMap<>();
        for (TimetableEntry e : locked) {
            placementBefore.put(e.getId(), new String[]{
                e.getDayOfWeek(),
                String.valueOf(e.getTimeSlot().getId()),
                String.valueOf(e.getClassroom().getId()),
                String.valueOf(e.getSubject().getId()),
                String.valueOf(e.getFaculty().getId())
            });
        }

        // Mirror the production flow (TimetableService.regenerateUnlockedSlots):
        // drop the unlocked rows from the DB before the engine re-places them,
        // otherwise fresh inserts collide with not-yet-flushed old rows under the
        // (timetable_id, day_of_week, time_slot_id) unique constraint. The cleared
        // persistence context then prevents the engine's locked-entry preservation
        // from scheduling orphan-removal deletes for the already-removed rows.
        entryRepository.deleteUnlockedByTimetableId(timetable.getId());
        entityManager.flush();
        entityManager.clear();
        timetable = timetableRepository.findById(timetable.getId()).orElseThrow();

        engine.generateSchedule(timetable, true);

        // Locked entries survive untouched (pinned by @PlanningPin).
        for (TimetableEntry e : locked) {
            String[] before = placementBefore.get(e.getId());
            assertNotNull(before, "locked entry must still be present");
            assertTrue(Boolean.TRUE.equals(e.getIsLocked()), "locked entry must remain locked");
            assertEquals(before[0], e.getDayOfWeek(), "locked entry day must be preserved");
            assertEquals(before[1], String.valueOf(e.getTimeSlot().getId()), "locked entry slot must be preserved");
            assertEquals(before[2], String.valueOf(e.getClassroom().getId()), "locked entry room must be preserved");
            assertEquals(before[3], String.valueOf(e.getSubject().getId()), "locked entry subject must be preserved");
            assertEquals(before[4], String.valueOf(e.getFaculty().getId()), "locked entry faculty must be preserved");
        }

        // Demand is backfilled around the locked periods and the result is feasible.
        assertEquals(expectedDemand(timetable), timetable.getEntries().size(),
            "partial regeneration must restore full weekly coverage");
        assertTrue(timetable.getConflicts().isEmpty(),
            "partial regeneration must not introduce conflicts: " + timetable.getConflicts());
        assertEquals(0, timetable.getConflictCount());

        // No double-booked faculty / room / section across locked + regenerated entries.
        Set<String> facultyKeys = new HashSet<>();
        Set<String> roomKeys = new HashSet<>();
        Set<String> sectionKeys = new HashSet<>();
        for (TimetableEntry e : timetable.getEntries()) {
            String window = e.getDayOfWeek() + "_" + e.getTimeSlot().getId();
            assertTrue(facultyKeys.add(e.getFaculty().getId() + "_" + window),
                "faculty double-booked at " + window);
            assertTrue(roomKeys.add(e.getClassroom().getId() + "_" + window),
                "room double-booked at " + window);
            assertTrue(sectionKeys.add(e.getSection().getId() + "_" + window),
                "section double-booked at " + window);
        }
    }

    private Timetable buildTimetable() {
        Department cse = departmentRepository.findByName("Computer Science & Engineering").orElseThrow();
        AcademicYear year1 = cse.getAcademicYears().get(0);
        Section section = year1.getSections().get(0);
        section.setStudentStrength(40); // seeded LAB room capacity is 40

        return Timetable.builder()
            .academicSession("2025-2026 EVEN")
            .department(cse)
            .section(section)
            .semester(3)
            .status("DRAFT")
            .build();
    }

    /** Weekly demand in lessons: theory + practical hours per subject (component-based). */
    private int expectedDemand(Timetable timetable) {
        List<Subject> subjects = curriculumDataLoader.loadSubjectsForTimetable(timetable);
        Map<Long, Integer> weekly = subjectDemandService.calculateWeeklyHours(subjects);
        int demand = 0;
        for (Subject s : subjects) {
            if (!Boolean.TRUE.equals(s.getIsActive())) continue;
            demand += weekly.getOrDefault(s.getId(), 0);
        }
        return demand;
    }
}
