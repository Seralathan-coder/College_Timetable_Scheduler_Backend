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
import com.erp.timetable.module.timetable.entity.TimetableConflict;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused regression tests for the live CSE Section 662 curriculum defects
 * (41/42 entries, labs clustered MON/TUE/WED at P1+P2).
 *
 * <p>Proves, on a fresh isolated department mirroring the real dataset
 * (6 theory subjects of 36 hours + 3 two-period lab subjects racing exactly
 * 42 slots): every demanded period is scheduled (42/42, per-subject exact),
 * lab sessions stay strict consecutive blocks on DISTINCT spread-out days with
 * DIFFERENT start periods (never the MON/TUE/WED + P1/P2 cluster), and no hard
 * constraint is weakened.
 */
@SpringBootTest
@ActiveProfiles("h2")
class TimetableGeneratorEngineCapacityAndLabDistributionTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private TimetableGeneratorEngine engine;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private FacultyRepository facultyRepository;

    @Test
    @Transactional
    void userCurriculum_fortyTwoDemand_schedulesEveryPeriod_labsDistributedAndConsecutive() {
        Department dept = newDepartment("CVB");
        Section section = dept.getAcademicYears().get(0).getSections().get(0);

        // Curriculum mirroring the live CSE 662 data: theory 36 + 3 labs × 2 = 42.
        Faculty phi = newFaculty(dept, "PHYS");
        Faculty cee = newFaculty(dept, "CPRG");
        Faculty tam = newFaculty(dept, "TAM");
        Faculty pet = newFaculty(dept, "PT");
        Faculty eng = newFaculty(dept, "ENG");
        Faculty mat = newFaculty(dept, "MATH");
        Faculty evn = newFaculty(dept, "EVS");

        List<Subject> subjects = List.of(
            newSubject(dept, section, phi, "CS115", "Physics", 5, 2, 1),
            newSubject(dept, section, cee, "CS472", "C", 6, 2, 2),
            newSubject(dept, section, tam, "CS505", "Tamil", 5, 0, 1),
            newSubject(dept, section, pet, "CS749", "PT", 2, 0, 1),
            newSubject(dept, section, eng, "CS765", "English", 5, 0, 1),
            newSubject(dept, section, mat, "CS864", "Maths", 8, 0, 2),
            newSubject(dept, section, evn, "CS876", "EVS", 5, 2, 1));

        Timetable timetable = newTimetable(dept, section);

        engine.generateSchedule(timetable);

        List<TimetableEntry> entries = timetable.getEntries();
        assertEquals(42, entries.size(),
            "exactly-full 42-demand curriculum must fill all 42 slots, got " + entries.size() + ": " + dump(entries));

        // Per-subject Required vs Scheduled (Difference must be 0 everywhere).
        for (Subject s : subjects) {
            int theory = (int) theoryOf(entries, s);
            int lab = (int) labOf(entries, s);
            assertEquals(s.getTheoryHours().intValue(), theory,
                s.getSubjectCode() + " theory: required=" + s.getTheoryHours() + " scheduled=" + theory);
            assertEquals(s.getPracticalHours().intValue(), lab,
                s.getSubjectCode() + " lab: required=" + s.getPracticalHours() + " scheduled=" + lab);
        }

        assertNoClashes(entries);
        assertNoBreakSlot(entries);
        assertDailyFacultyCapAtMostFive(entries);
        assertNoConflicts(timetable);

        // Lab distribution invariants.
        List<Subject> labSubjects = subjects.stream().filter(s -> s.getPracticalHours() > 0).toList();
        assertEquals(3, labSubjects.size());

        // 1. Every lab is a SINGLE strict consecutive non-break run of its 2 periods.
        for (Subject s : labSubjects) {
            List<Integer> runs = runSizes(entriesOf(entries, s).stream()
                .filter(TimetableEntry::isLab).toList());
            assertEquals(List.of(2), runs,
                s.getSubjectCode() + " must be one 2-period consecutive block: " + runs);
        }

        // 2. The three lab blocks land on three DIFFERENT days...
        List<String> labDays = labSubjects.stream()
            .flatMap(s -> entriesOf(entries, s).stream().filter(TimetableEntry::isLab))
            .map(TimetableEntry::getDayOfWeek).distinct().sorted().toList();
        assertEquals(3, labDays.size(),
            "3 practical blocks must occupy 3 distinct days, got: " + labDays);

        // ...and NOT on three consecutive weekdays (the old MON/TUE/WED cluster).
        assertFalse(isThreeConsecutiveDays(labDays),
            "lab blocks must not cluster onto consecutive days: " + labDays);

        // 3. Lab start periods differ between blocks (never the all-P1+P2 cluster).
        List<Integer> labStarts = labSubjects.stream()
            .map(s -> entriesOf(entries, s).stream().filter(TimetableEntry::isLab)
                .map(e -> e.getTimeSlot().getSlotOrder()).sorted().findFirst().orElse(-1))
            .sorted().toList();
        assertTrue(labStarts.stream().distinct().count() >= 2,
            "at least two practical blocks must start at DIFFERENT periods, got: " + labStarts);

        // 4. No lab on Saturday.
        assertTrue(labSubjects.stream()
            .flatMap(s -> entriesOf(entries, s).stream().filter(TimetableEntry::isLab))
            .noneMatch(e -> "SAT".equals(e.getDayOfWeek())));

        System.out.println(">>> CVB42 entries=" + entries.size()
            + " labDays=" + labDays + " labStarts=" + labStarts
            + " subjectHours=" + perSubjectHours(entries, subjects));
        for (Subject s : labSubjects) {
            List<TimetableEntry> labs = entriesOf(entries, s).stream()
                .filter(TimetableEntry::isLab).toList();
            System.out.println(">>> LAB " + s.getSubjectCode() + " "
                + labs.stream()
                    .map(e -> e.getDayOfWeek() + ":" + e.getTimeSlot().getSlotOrder())
                    .sorted().collect(Collectors.joining(",")));
        }
    }

    @Test
    @Transactional
    void labDistribution_isShuffleProof_acrossMultipleGenerationRuns() {
        // The practical-subject order is shuffled per generation; the rotation
        // must keep the spread (distinct non-consecutive days + distinct start
        // periods) REGARDLESS of which lab lands on index 0/1/2.
        for (int run = 0; run < 5; run++) {
            Department dept = newDepartment("CVS");
            Section section = dept.getAcademicYears().get(0).getSections().get(0);
            List<Subject> labSubjects = List.of(
                newSubject(dept, section, newFaculty(dept, "LA"), "CSL1", "Lab Alpha", 2, 2, 1),
                newSubject(dept, section, newFaculty(dept, "LB"), "CSL2", "Lab Beta", 2, 2, 1),
                newSubject(dept, section, newFaculty(dept, "LC"), "CSL3", "Lab Gamma", 2, 2, 1));

            Timetable timetable = newTimetable(dept, section);
            engine.generateSchedule(timetable);

            List<TimetableEntry> entries = timetable.getEntries();
            assertNoClashes(entries);
            assertNoConflicts(timetable);

            List<String> labDays = labSubjects.stream()
                .flatMap(s -> entriesOf(entries, s).stream().filter(TimetableEntry::isLab))
                .map(TimetableEntry::getDayOfWeek).distinct().sorted().toList();
            assertEquals(3, labDays.size(), "run " + run + ": labs on distinct days: " + labDays);
            assertFalse(isThreeConsecutiveDays(labDays),
                "run " + run + ": labs must never cluster onto consecutive days: " + labDays);

            List<Integer> labStarts = labSubjects.stream()
                .map(s -> entriesOf(entries, s).stream().filter(TimetableEntry::isLab)
                    .map(e -> e.getTimeSlot().getSlotOrder()).sorted().findFirst().orElse(-1))
                .sorted().toList();
            assertTrue(labStarts.stream().distinct().count() >= 2,
                "run " + run + ": lab start periods must differ: " + labStarts);

            for (Subject s : labSubjects) {
                List<Integer> runs = runSizes(entriesOf(entries, s).stream()
                    .filter(TimetableEntry::isLab).toList());
                assertEquals(List.of(2), runs, s.getSubjectCode() + " consecutive block expected: " + runs);
            }
        }
    }

    // ── fixtures (fresh, isolated, rolled back by @Transactional) ────────────

    private Department newDepartment(String prefix) {
        int n = COUNTER.getAndIncrement();
        Department dept = Department.builder()
            .name(prefix + "-" + n)
            .hodName("Fixed Lab HOD")
            .contactEmail(prefix.toLowerCase() + n + "@local.test")
            .contactPhone("000")
            .building("Fixed Block")
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
            .firstName("Fix")
            .lastName(employeeId + n)
            .email(employeeId.toLowerCase() + n + "@local.test")
            .department(dept)
            .designation("Professor")
            .status("AVAILABLE")
            .build());
    }

    private Subject newSubject(Department dept, Section section, Faculty faculty,
            String code, String name, int theory, int practical, Integer blockSize) {
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
            .subjectType("THEORY")
            .sessionBlockSize(blockSize)
            .isActive(true)
            .build());
    }

    private Timetable newTimetable(Department dept, Section section) {
        return Timetable.builder()
            .academicSession("2025-2026 EVEN")
            .department(dept)
            .section(section)
            .semester(1)
            .status("DRAFT")
            .build();
    }

    // ── assertions ───────────────────────────────────────────────────────────

    private void assertNoClashes(List<TimetableEntry> entries) {
        Set<String> sectionKeys = new HashSet<>();
        Set<String> facultyKeys = new HashSet<>();
        Set<String> roomKeys = new HashSet<>();
        for (TimetableEntry e : entries) {
            String window = e.getDayOfWeek() + "_" + e.getTimeSlot().getId();
            assertTrue(sectionKeys.add(e.getSection().getId() + "_" + window),
                "section double-booked at " + window + " (" + dump(List.of(e)) + ")");
            assertTrue(facultyKeys.add(e.getFaculty().getId() + "_" + window),
                "faculty double-booked at " + window + " (" + dump(List.of(e)) + ")");
            assertTrue(roomKeys.add(e.getClassroom().getId() + "_" + window),
                "room double-booked at " + window + " (" + dump(List.of(e)) + ")");
        }
    }

    private void assertNoBreakSlot(List<TimetableEntry> entries) {
        for (TimetableEntry e : entries) {
            assertTrue(e.getTimeSlot().getSlotOrder() != 5,
                "no entry may land on the break slot (slot order 5): " + dump(List.of(e)));
        }
    }

    private void assertDailyFacultyCapAtMostFive(List<TimetableEntry> entries) {
        Map<String, Long> dailyLoad = entries.stream().collect(Collectors.groupingBy(
            e -> e.getFaculty().getId() + "_" + e.getDayOfWeek(), Collectors.counting()));
        assertTrue(dailyLoad.values().stream().allMatch(v -> v <= 5),
            "faculty daily load exceeded the college cap of 5: " + dailyLoad);
    }

    private void assertNoConflicts(Timetable t) {
        List<TimetableConflict> hard = t.getConflicts().stream()
            .filter(c -> "HIGH".equalsIgnoreCase(c.getSeverity()))
            .toList();
        assertTrue(hard.isEmpty(), "unexpected HIGH conflicts on a feasible solve: " + hard);
        assertEquals(0, t.getConflicts().size(),
            "unexpected conflicts on the 42/42 solve: " + t.getConflicts());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<TimetableEntry> entriesOf(List<TimetableEntry> all, Subject s) {
        return all.stream().filter(e -> e.getSubject() != null && e.getSubject().getId().equals(s.getId())).toList();
    }

    private static long theoryOf(List<TimetableEntry> all, Subject s) {
        return entriesOf(all, s).stream().filter(e -> !e.isLab()).count();
    }

    private static long labOf(List<TimetableEntry> all, Subject s) {
        return entriesOf(all, s).stream().filter(TimetableEntry::isLab).count();
    }

    private static List<Integer> runSizes(List<TimetableEntry> labEntries) {
        Map<String, List<Integer>> byDay = labEntries.stream().collect(Collectors.groupingBy(
            TimetableEntry::getDayOfWeek,
            Collectors.mapping(e -> e.getTimeSlot().getSlotOrder(), Collectors.toList())));
        List<Integer> runs = new ArrayList<>();
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

    private static boolean isThreeConsecutiveDays(List<String> days) {
        List<String> week = List.of("MON", "TUE", "WED", "THU", "FRI");
        List<Integer> idx = days.stream()
            .map(d -> week.indexOf(d))
            .filter(i -> i >= 0)
            .sorted()
            .toList();
        for (int i = 0; i + 2 < idx.size(); i++) {
            List<Integer> triple = idx.subList(i, i + 3);
            boolean consecutive = true;
            for (int k = 1; k < triple.size(); k++) {
                if (triple.get(k) != triple.get(k - 1) + 1) {
                    consecutive = false;
                    break;
                }
            }
            if (consecutive) {
                return true;
            }
        }
        return false;
    }

    private static String perSubjectHours(List<TimetableEntry> entries, List<Subject> subjects) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Subject s : subjects) {
            out.put(s.getSubjectCode(),
                "T=" + theoryOf(entries, s) + " L=" + labOf(entries, s));
        }
        return out.toString();
    }

    private static String dump(List<TimetableEntry> entries) {
        return entries.stream()
            .map(e -> e.getSubject() == null ? "?" : e.getSubject().getSubjectCode()
                + "@" + e.getDayOfWeek()
                + ":" + e.getTimeSlot().getSlotOrder()
                + (e.isLab() ? "(L)" : ""))
            .collect(Collectors.joining(", "));
    }
}