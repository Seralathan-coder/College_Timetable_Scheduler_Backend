package com.erp.timetable.module.timetable.engine;

import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.AcademicYearRepository;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.department.repository.SectionRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.entity.TimetableConflict;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots the full Spring context on H2 (with seeded master data) and exercises
 * the scheduling engine end-to-end, asserting every hard constraint holds on
 * the generated timetable.
 */
@SpringBootTest
@ActiveProfiles("h2")
class TimetableGeneratorEngineIntegrationTest {

    @Autowired
    private TimetableGeneratorEngine engine;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private FacultyRepository facultyRepository;

    @Autowired
    private ClassroomRepository classroomRepository;

    @Autowired
    private AcademicYearRepository academicYearRepository;

    @Autowired
    private SectionRepository sectionRepository;

    @Test
    @Transactional
    void generateSchedule_placesConfiguredDoublePeriodAsConsecutiveBlock() {
        Department cse = departmentRepository.findByName("Computer Science & Engineering").orElseThrow();
        AcademicYear year1 = cse.getAcademicYears().get(0);
        Section section = year1.getSections().get(0);
        section.setStudentStrength(40); // seeded LAB room capacity is 40

        // A theory subject needing 4 periods/week (theoryHours=4) configured to
        // run as 2 consecutive periods per session → 4 weekly hours, blockSize 2
        // → 2 block-sessions → 2 × 2-slot blocks.
        Faculty faculty = facultyRepository.findByEmployeeId("FAC001").orElseThrow();
        Subject blockSubject = subjectRepository.save(Subject.builder()
            .subjectCode("CSBLK01")
            .subjectName("Double Period Subject")
            .department(cse)
            .academicYear(year1)
            .section(section)
            .assignedFaculty(faculty)
            .semester(3)
            .credits(4)
            .theoryHours(4)
            .practicalHours(0)
            .subjectType("THEORY")
            .sessionBlockSize(2)
            .isActive(true)
            .build());

        Timetable timetable = Timetable.builder()
            .academicSession("2025-2026 EVEN")
            .department(cse)
            .section(section)
            .semester(3)
            .status("DRAFT")
            .build();

        engine.generateSchedule(timetable);

        List<TimetableEntry> blockEntries = timetable.getEntries().stream()
            .filter(e -> e.getSubject() != null && e.getSubject().getId().equals(blockSubject.getId()))
            .toList();

        // 1. All 4 periods placed.
        assertEquals(4, blockEntries.size(),
            "double-period subject must be placed as 4 slots: " + blockEntries);

        // 2. The NORMAL-subject distribution rule decides the shape from the
        // weekly HOURS, not from the stored sessionBlockSize: a 4-hour subject
        // is below the 5-period single-period threshold, so its plan is 1+1+1+1 —
        // one period on each of 4 distinct days, never 4 periods on one day and
        // never a 3- or 4-period run.
        Map<String, Long> perDay = blockEntries.stream()
            .collect(Collectors.groupingBy(TimetableEntry::getDayOfWeek, Collectors.counting()));
        assertEquals(4, perDay.size(),
            "a 4-hour NORMAL subject must use 4 distinct teaching days: " + blockEntries);
        assertTrue(perDay.values().stream().allMatch(count -> count == 1L),
            "no teaching day may hold more than one period of this subject: " + blockEntries);
        List<Integer> runSizes = consecutiveRunSizes(blockEntries);
        assertEquals(List.of(1, 1, 1, 1), runSizes,
            "each period stands alone under the single-period pattern: " + runSizes);
        assertTrue(blockEntries.stream().noneMatch(e -> e.getTimeSlot().getSlotOrder() == 5),
            "no period may be a break slot (slot 5): " + blockEntries);
        // 3. The assigned faculty teaches every block slot (manual assignment preserved).
        assertTrue(blockEntries.stream().allMatch(e -> e.getFaculty().getId().equals(faculty.getId())));

        // 4. No hard conflicts.
        List<TimetableConflict> hardConflicts = timetable.getConflicts().stream()
            .filter(c -> "HIGH".equalsIgnoreCase(c.getSeverity()))
            .toList();
        assertTrue(hardConflicts.isEmpty(), "unexpected HIGH conflicts: " + hardConflicts);

        // 5. No double-booked faculty / room / section at any (day, slot).
        Set<String> facultyKeys = new HashSet<>();
        Set<String> roomKeys = new HashSet<>();
        Set<String> sectionKeys = new HashSet<>();
        for (TimetableEntry e : timetable.getEntries()) {
            assertTrue(facultyKeys.add(e.getFaculty().getId() + "_" + e.getDayOfWeek() + "_" + e.getTimeSlot().getId()));
            assertTrue(roomKeys.add(e.getClassroom().getId() + "_" + e.getDayOfWeek() + "_" + e.getTimeSlot().getId()));
            assertTrue(sectionKeys.add(e.getSection().getId() + "_" + e.getDayOfWeek() + "_" + e.getTimeSlot().getId()));
        }
    }

    @Test
    @Transactional
    void generateSchedule_blockSizeMustNeverExceedWeeklyHours() {
        Department cse = departmentRepository.findByName("Computer Science & Engineering").orElseThrow();
        AcademicYear year1 = cse.getAcademicYears().get(0);
        Section section = year1.getSections().get(0);
        Faculty faculty = facultyRepository.findByEmployeeId("FAC001").orElseThrow();

        // weekly hours = theoryHours = 5, blockSize = 2 → floor(5/2)=2 block-sessions
        // (4 periods) + 1 remainder single session. Total must be exactly 5, never 6.
        Subject blockSubject = subjectRepository.save(Subject.builder()
            .subjectCode("CSBLK02")
            .subjectName("Remainder Subject")
            .department(cse)
            .academicYear(year1)
            .section(section)
            .assignedFaculty(faculty)
            .semester(3)
            .credits(4)
            .theoryHours(5)
            .practicalHours(0)
            .subjectType("THEORY")
            .sessionBlockSize(2)
            .isActive(true)
            .build());

        Timetable timetable = Timetable.builder()
            .academicSession("2025-2026 EVEN")
            .department(cse)
            .section(section)
            .semester(3)
            .status("DRAFT")
            .build();

        engine.generateSchedule(timetable);

        List<TimetableEntry> entries = timetable.getEntries().stream()
            .filter(e -> e.getSubject() != null && e.getSubject().getId().equals(blockSubject.getId()))
            .toList();

        // Total periods must equal weekly hours (5), not ceil-scaled (6).
        assertEquals(5, entries.size(),
            "blockSize x sessions must never exceed weekly hours: " + entries);

        // H=5, block=2 → doubleDays=max(0,5-5)=0, singleDays=5: five single-period
        // sessions on five distinct days (new idealDaySizes distribution).
        List<Integer> runSizes = consecutiveRunSizes(entries).stream().sorted().toList();
        assertEquals(List.of(1, 1, 1, 1, 1), runSizes,
            "H=5 block=2 produces five single-period sessions: " + runSizes);
        assertEquals(5, entries.stream().map(TimetableEntry::getDayOfWeek).distinct().count(),
            "H=5 block=2 must spread across five distinct days: " + entries);
    }

    @Test
    @Transactional
    void generateSchedule_labSubjectWithSessionBlockSizeThree_placesSingleThreePeriodBlock() {
        Department cse = departmentRepository.findByName("Computer Science & Engineering").orElseThrow();
        AcademicYear year1 = cse.getAcademicYears().get(0);
        Section section = year1.getSections().get(0);
        section.setStudentStrength(40); // seeded LAB room capacity is 40
        Faculty faculty = facultyRepository.findByEmployeeId("FAC001").orElseThrow();

        // A LAB subject with 3 practical hours and an explicit per-subject
        // sessionBlockSize of 3 must be placed as ONE 3-consecutive-period block
        // in a LAB room — NOT as 2+1 (the pre-feature global-block-size result).
        Subject lab = subjectRepository.save(Subject.builder()
            .subjectCode("CSBLK03")
            .subjectName("Triple Block Lab")
            .department(cse)
            .academicYear(year1)
            .section(section)
            .assignedFaculty(faculty)
            .semester(3)
            .credits(2)
            .theoryHours(0)
            .practicalHours(3)
            .subjectType("LAB")
            .sessionBlockSize(3)
            .isActive(true)
            .build());

        Timetable timetable = Timetable.builder()
            .academicSession("2025-2026 EVEN")
            .department(cse)
            .section(section)
            .semester(3)
            .status("DRAFT")
            .build();

        engine.generateSchedule(timetable);

        List<TimetableEntry> labEntries = timetable.getEntries().stream()
            .filter(e -> e.getSubject() != null && e.getSubject().getId().equals(lab.getId()))
            .toList();

        // 1. All 3 practical periods placed, in a single LAB room.
        assertEquals(3, labEntries.size(),
            "3 practical hours with per-subject block 3 must be placed as 3 periods: " + labEntries);
        assertTrue(labEntries.stream().allMatch(e -> "LAB".equalsIgnoreCase(e.getClassroom().getRoomType())),
            "practical periods must use a LAB room");
        assertEquals(1, labEntries.stream().map(e -> e.getClassroom().getId()).collect(Collectors.toSet()).size(),
            "all practical periods must use the same LAB room");

        // 2. The 3 periods form exactly one strictly-consecutive run on one day
        //    (a 3-period block), never 2+1.
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
        assertEquals(List.of(3), runSizes,
            "per-subject block size 3 must produce a single 3-period consecutive block: " + runSizes);

        // 3. No hard conflicts.
        List<TimetableConflict> hardConflicts = timetable.getConflicts().stream()
            .filter(c -> "HIGH".equalsIgnoreCase(c.getSeverity()))
            .toList();
        assertTrue(hardConflicts.isEmpty(), "unexpected HIGH conflicts: " + hardConflicts);
    }

    @Test
    @Transactional
    void generateSchedule_sessionBlockSizes_1_2_and3_produceOneTwoThreePeriodSessions() {
        Department cse = departmentRepository.findByName("Computer Science & Engineering").orElseThrow();
        AcademicYear year1 = cse.getAcademicYears().get(0);
        Section section = year1.getSections().get(0);
        section.setStudentStrength(40); // seeded LAB room capacity is 40
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
            .department(cse)
            .academicYear(year1)
            .section(section)
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
            .department(cse)
            .academicYear(year1)
            .section(section)
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
            .department(cse)
            .academicYear(year1)
            .section(section)
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
            Timetable timetable = Timetable.builder()
                .academicSession("2025-2026 EVEN")
                .department(cse)
                .section(section)
                .semester(3)
                .status("DRAFT")
                .build();

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

            // Part 2: Lab always uses full practicalHours as one block, ignoring the dropdown.
            // All three lab subjects have practicalHours=3, so each produces one 3-period block.
            assertEquals(List.of(3), consecutiveRunSizes(entriesOf(timetable, blk1)).stream().sorted().toList(),
                "lab practicalHours=3 always produces one 3-period block (dropdown ignored): "
                    + consecutiveRunSizes(entriesOf(timetable, blk1)));
            assertEquals(List.of(3), consecutiveRunSizes(entriesOf(timetable, blk2)).stream().sorted().toList(),
                "lab practicalHours=3 always produces one 3-period block (dropdown ignored): "
                    + consecutiveRunSizes(entriesOf(timetable, blk2)));
            assertEquals(List.of(3), consecutiveRunSizes(entriesOf(timetable, blk3)).stream().sorted().toList(),
                "lab practicalHours=3 always produces one 3-period block: "
                    + consecutiveRunSizes(entriesOf(timetable, blk3)));

            List<TimetableConflict> hardConflicts = timetable.getConflicts().stream()
                .filter(c -> "HIGH".equalsIgnoreCase(c.getSeverity()))
                .toList();
            assertTrue(hardConflicts.isEmpty(), "unexpected HIGH conflicts: " + hardConflicts);
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
    @Transactional
    void generateSchedule_excludesClassroomScopedToDifferentSection() {
        Department cse = departmentRepository.findByName("Computer Science & Engineering").orElseThrow();
        AcademicYear year1 = cse.getAcademicYears().get(0);
        Section section = year1.getSections().get(0);
        section.setStudentStrength(40); // seeded LAB room capacity is 40

        // A brand-new LAB classroom owned by ANOTHER section of the same year.
        // With NULL year/section the engine would prefer this room (least-used),
        // so the assertion is meaningful: after scoping it must be skipped.
        Section otherSection = sectionRepository.save(Section.builder()
            .academicYear(year1)
            .name("SCOPED-X")
            .studentStrength(40)
            .status("ACTIVE")
            .build());
        Classroom scopedLab = classroomRepository.save(Classroom.builder()
            .roomNumber("TMP-SCOPED-LAB")
            .building("Block X")
            .department(cse)
            .academicYear(year1)
            .section(otherSection)
            .roomType("LAB")
            .capacity(60)
            .status("AVAILABLE")
            .build());

        try {
            Timetable timetable = Timetable.builder()
                .academicSession("2025-2026 EVEN")
                .department(cse)
                .section(section)
                .semester(3)
                .status("DRAFT")
                .build();

            engine.generateSchedule(timetable);

            assertFalse(timetable.getEntries().isEmpty(), "engine must place entries");
            boolean usedScoped = timetable.getEntries().stream()
                .anyMatch(e -> scopedLab.getId().equals(e.getClassroom().getId()));
            assertFalse(usedScoped,
                "engine must not place lessons in a classroom scoped to a different section: " + scopedLab.getRoomNumber());
        } finally {
            classroomRepository.delete(scopedLab);
            sectionRepository.delete(otherSection);
            classroomRepository.flush();
        }
    }

    @Test
    @Transactional
    void generateSchedule_dynamicDemand_subjectsXAndY_placeNineLessons() {
        Department cse = departmentRepository.findByName("Computer Science & Engineering").orElseThrow();
        AcademicYear year1 = cse.getAcademicYears().get(0);
        Section section = year1.getSections().get(0);
        section.setStudentStrength(40); // seeded LAB room capacity is 40
        Faculty faculty = facultyRepository.findByEmployeeId("FAC001").orElseThrow();

        // Dynamic-demand dataset (temporary, isolated records — removed below and
        // additionally rolled back by @Transactional):
        //   Subject X: 3 theory + 2 practical hours, per-subject block 2.
        //   Subject Y: 4 theory hours, default block (single periods).
        // Expected: 3 + 2 + 4 = 9 lessons, driven purely by the subject records.
        Subject subjectX = subjectRepository.save(Subject.builder()
            .subjectCode("TMPX")
            .subjectName("Dynamic Subject X")
            .department(cse)
            .academicYear(year1)
            .section(section)
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
            .department(cse)
            .academicYear(year1)
            .section(section)
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
            Timetable timetable = Timetable.builder()
                .academicSession("2025-2026 EVEN")
                .department(cse)
                .section(section)
                .semester(3)
                .status("DRAFT")
                .build();

            engine.generateSchedule(timetable);

            List<TimetableEntry> xEntries = timetable.getEntries().stream()
                .filter(e -> e.getSubject() != null && e.getSubject().getId().equals(subjectX.getId()))
                .toList();
            List<TimetableEntry> yEntries = timetable.getEntries().stream()
                .filter(e -> e.getSubject() != null && e.getSubject().getId().equals(subjectY.getId()))
                .toList();

            // 1. Demand derived from the subject records alone: 3 + 2 + 4 = 9.
            assertEquals(9, xEntries.size() + yEntries.size(),
                "dynamic demand must be theory + practical hours of the subject records: "
                    + xEntries.size() + " + " + yEntries.size());

            // 2. X theory = 3 periods (block 2 + H=3 → 3 single-period sessions).
            List<TimetableEntry> xTheory = xEntries.stream()
                .filter(e -> !Boolean.TRUE.equals(e.getIsLab()))
                .toList();
            assertEquals(3, xTheory.size(), "X must place 3 theory periods: " + xTheory);
            List<Integer> xTheoryRuns = consecutiveRunSizes(xTheory);
            assertEquals(List.of(1, 1, 1), xTheoryRuns,
                "X theory (block 2, H=3) must be 3 single-period sessions: " + xTheoryRuns);

            // 3. X practical = 2 periods in a LAB room.
            List<TimetableEntry> xPractical = xEntries.stream()
                .filter(TimetableEntry::getIsLab)
                .toList();
            assertEquals(2, xPractical.size(), "X must place 2 practical periods: " + xPractical);
            assertTrue(xPractical.stream().allMatch(e -> "LAB".equalsIgnoreCase(e.getClassroom().getRoomType())),
                "X practical periods must use a LAB room");

            // 4. Y theory = 4 single periods.
            assertEquals(4, yEntries.size(), "Y must place 4 theory periods: " + yEntries);
            assertTrue(yEntries.stream().noneMatch(TimetableEntry::getIsLab),
                "Y (0 practical hours) must have no lab entries");

            // 5. No hard conflicts.
            List<TimetableConflict> hardConflicts = timetable.getConflicts().stream()
                .filter(c -> "HIGH".equalsIgnoreCase(c.getSeverity()))
                .toList();
            assertTrue(hardConflicts.isEmpty(), "unexpected HIGH conflicts: " + hardConflicts);
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
    @Transactional
    void generateSchedule_keepsSinglePeriodSubjectsUnchanged() {
        Department cse = departmentRepository.findByName("Computer Science & Engineering").orElseThrow();
        AcademicYear year1 = cse.getAcademicYears().get(0);
        Section section = year1.getSections().get(0);

        Timetable timetable = Timetable.builder()
            .academicSession("2025-2026 EVEN")
            .department(cse)
            .section(section)
            .semester(3)
            .status("DRAFT")
            .build();

        engine.generateSchedule(timetable);

        // Seed theory subjects (sessionBlockSize = 1, default) spread their
        // periods across distinct working days: one period per day, as many
        // distinct days as the subject's weekly hours require (up to 6).
        List<Subject> theorySubjects = subjectRepository.findBySectionIdIn(List.of(section.getId())).stream()
            .filter(s -> !"LAB".equalsIgnoreCase(s.getSubjectType()))
            .toList();
        for (Subject s : theorySubjects) {
            List<TimetableEntry> entries = timetable.getEntries().stream()
                .filter(e -> e.getSubject() != null && e.getSubject().getId().equals(s.getId()))
                .toList();
            assertFalse(entries.isEmpty(), s.getSubjectCode() + " must be placed");
            long distinctDays = entries.stream().map(TimetableEntry::getDayOfWeek).distinct().count();
            int expectedPeriods = s.getTheoryHours() != null ? s.getTheoryHours() : 0;
            // Each period must land on a distinct day whenever the subject has
            // fewer periods than working days — no doubling up.
            assertTrue(distinctDays >= Math.min(expectedPeriods, 6),
                "single-period subject " + s.getSubjectCode()
                    + " must spread across " + expectedPeriods + " distinct days, got " + distinctDays
                    + " days: " + entries);
            for (TimetableEntry e : entries) {
                assertNotNull(e.getTimeSlot());
            }
        }
    }

    @Test
    @Transactional
    void generateSchedule_producesValidConflictFreeTimetable() {
        Department cse = departmentRepository.findByName("Computer Science & Engineering").orElseThrow();
        AcademicYear year1 = cse.getAcademicYears().get(0);
        Section section = year1.getSections().get(0);
        section.setStudentStrength(40); // seeded LAB room capacity is 40

        List<Subject> sectionSubjects = subjectRepository.findBySectionIdIn(List.of(section.getId()));
        assertFalse(sectionSubjects.isEmpty(), "seed data must attach subjects to the section");

        Timetable timetable = Timetable.builder()
            .academicSession("2025-2026 EVEN")
            .department(cse)
            .section(section)
            .semester(3)
            .status("DRAFT")
            .build();

        engine.generateSchedule(timetable);

        assertFalse(timetable.getEntries().isEmpty(), "engine must place entries");
        assertEquals("GENERATED", timetable.getStatus());

        // 1. No HIGH (hard-constraint) conflicts may remain.
        List<TimetableConflict> hardConflicts = timetable.getConflicts().stream()
            .filter(c -> "HIGH".equalsIgnoreCase(c.getSeverity()))
            .toList();
        assertTrue(hardConflicts.isEmpty(),
            "unexpected HIGH conflicts: " + hardConflicts);

        // 2. No faculty double-booked per (day, slot).
        Set<String> facultyKeys = new HashSet<>();
        for (TimetableEntry e : timetable.getEntries()) {
            String k = e.getFaculty().getId() + "_" + e.getDayOfWeek() + "_" + e.getTimeSlot().getId();
            assertTrue(facultyKeys.add(k), "faculty double-booked at: " + k);
        }

        // 3. No room double-booked per (day, slot).
        Set<String> roomKeys = new HashSet<>();
        for (TimetableEntry e : timetable.getEntries()) {
            String k = e.getClassroom().getId() + "_" + e.getDayOfWeek() + "_" + e.getTimeSlot().getId();
            assertTrue(roomKeys.add(k), "room double-booked at: " + k);
        }

        // 4. No section double-booked per (day, slot).
        Set<String> sectionKeys = new HashSet<>();
        for (TimetableEntry e : timetable.getEntries()) {
            String k = e.getSection().getId() + "_" + e.getDayOfWeek() + "_" + e.getTimeSlot().getId();
            assertTrue(sectionKeys.add(k), "section double-booked at: " + k);
        }

        // 5. Faculty daily load never exceeds the college-wide ceiling of 5
        //    periods (seed faculty records cap themselves at 4).
        Map<String, Integer> dailyLoad = new HashMap<>();
        for (TimetableEntry e : timetable.getEntries()) {
            String k = e.getFaculty().getId() + "_" + e.getDayOfWeek();
            dailyLoad.merge(k, 1, Integer::sum);
        }
        assertTrue(dailyLoad.values().stream().allMatch(v -> v <= 5),
            "faculty daily load exceeded 5: " + dailyLoad);

        // 6. Lab (CS205L: 3 practical hours) carries the DB-default sessionBlockSize
        //    of 1, which is honored verbatim: each practical hour is scheduled as a
        //    separate 1-period session — no 2+1 global-fallback grouping.
        List<TimetableEntry> labEntries = timetable.getEntries().stream()
            .filter(TimetableEntry::getIsLab)
            .toList();
        assertFalse(labEntries.isEmpty(), "lab subject must be placed");
        assertEquals(3, labEntries.size(), "lab subject must be placed as 3 practical periods");
        assertTrue(labEntries.stream().allMatch(e -> "LAB".equalsIgnoreCase(e.getClassroom().getRoomType())),
            "practical periods must use a LAB room");
        assertEquals(1, labEntries.stream().map(e -> e.getClassroom().getId()).collect(Collectors.toSet()).size(),
            "all practical periods must use the same LAB room");

        // Consecutive runs across all practical entries: sessionBlockSize 1 means
        // every session is exactly one period (never a run of 2+).
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

        // 7. Each theory subject receives its planned weekly coverage (theoryHours).
        Map<Long, Long> bySubject = timetable.getEntries().stream()
            .filter(e -> !Boolean.TRUE.equals(e.getIsLab()))
            .collect(Collectors.groupingBy(e -> e.getSubject().getId(), Collectors.counting()));
        for (Subject s : sectionSubjects) {
            if ("LAB".equalsIgnoreCase(s.getSubjectType())) continue;
            int expected = Math.max(1, s.getTheoryHours() != null ? s.getTheoryHours() : 0);
            assertEquals(expected, bySubject.getOrDefault(s.getId(), 0L).intValue(),
                "weekly coverage mismatch for " + s.getSubjectCode());
        }

        // 8. Optimization score is a sane percentage.
        assertNotNull(timetable.getOptimizationScore());
        assertTrue(timetable.getOptimizationScore() >= 0 && timetable.getOptimizationScore() <= 100,
            "optimization score out of range: " + timetable.getOptimizationScore());
    }

    @Test
    @Transactional
    void generateSchedule_labSessionsNeverOnSaturday_andDailyLoadNeverExceedsFive() {
        Department cse = departmentRepository.findByName("Computer Science & Engineering").orElseThrow();
        AcademicYear year1 = cse.getAcademicYears().get(0);
        Section section = year1.getSections().get(0);
        section.setStudentStrength(40); // seeded LAB room capacity is 40

        Timetable timetable = Timetable.builder()
            .academicSession("2025-2026 EVEN")
            .department(cse)
            .section(section)
            .semester(3)
            .status("DRAFT")
            .build();

        engine.generateSchedule(timetable);

        // 1. LAB sessions are NEVER scheduled on Saturday (college policy).
        List<TimetableEntry> labEntries = timetable.getEntries().stream()
            .filter(TimetableEntry::getIsLab)
            .toList();
        assertFalse(labEntries.isEmpty(), "lab subject must be placed");
        assertTrue(labEntries.stream().allMatch(e -> !"SAT".equals(e.getDayOfWeek())),
            "LAB sessions must never be scheduled on Saturday: " + labEntries);

        // 2. No faculty may teach more than the college-wide cap of 5 periods in
        //    a single day (seed faculty records cap themselves at 4).
        Map<String, Integer> dailyLoad = new HashMap<>();
        for (TimetableEntry e : timetable.getEntries()) {
            String k = e.getFaculty().getId() + "_" + e.getDayOfWeek();
            dailyLoad.merge(k, 1, Integer::sum);
        }
        assertTrue(dailyLoad.values().stream().allMatch(v -> v <= 5),
            "faculty daily load exceeded the college-wide cap of 5: " + dailyLoad);

        // 3. The tighter constraints must not have made the schedule infeasible.
        List<TimetableConflict> hardConflicts = timetable.getConflicts().stream()
            .filter(c -> "HIGH".equalsIgnoreCase(c.getSeverity()))
            .toList();
        assertTrue(hardConflicts.isEmpty(), "unexpected HIGH conflicts: " + hardConflicts);
    }
}
