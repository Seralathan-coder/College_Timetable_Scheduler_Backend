package com.erp.timetable.module.timetable.engine;

import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.availability.repository.TimeSlotRepository;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.erp.timetable.module.timetable.engine.constraint.ConstraintContext;
import com.erp.timetable.module.timetable.engine.shared.TimetableEntryMapper;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic direct-invocation regression tests for the engine's swap-repair
 * (TimetableGeneratorEngine#attemptSwapRepair), the generic recovery that fills
 * a stranded single theory period of a section whose ONLY free cell is blocked
 * for the missing subject's faculty (e.g. that faculty already teaches another
 * section at that slot).
 *
 * <p>These tests bypass the non-deterministic random generator: they seed a
 * fixed 41-entry partial layout (all cells except (SAT, order 7) occupied),
 * inject a cross-timetable block for the missing subject's faculty at that free
 * cell, build the constraint context exactly like the engine does mid-run, and
 * invoke the repair directly. They prove the repair:
 * <ul>
 *   <li>fills the 42nd slot legally when a feasible swap exists,</li>
 *   <li>skips candidates whose vacated cell is ALSO blocked and continues,</li>
 *   <li>returns false WITHOUT any mutation when no legal swap exists, and</li>
 *   <li>does not alter already-successful full generations.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("h2")
class TimetableGeneratorEngineSwapRepairTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT");

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
    private TimeSlotRepository timeSlotRepository;

    @Autowired
    private TimetableEntryMapper entryMapper;

    @Test
    @Transactional
    void swapRepair_fillsThe42ndSlot_whenOnlyFreeCellIsBlockedForMissingSubjectFaculty() {
        Fixture fix = seedStrandedLayout();

        // The only free cell (SAT, order 7) is blocked for the missing subject's
        // faculty elsewhere (cross-timetable): mop-up can never place it directly.
        TimeSlot order7 = fix.slotByOrder.get(7);
        fix.context.getFacultyOccupancy()
            .computeIfAbsent("SAT_" + order7.getId(), k -> new HashSet<>()).add(fix.missingFaculty.getId());

        boolean repaired = engine.attemptSwapRepair(
            fix.timetable, fix.missing, List.of(fix.missingFaculty),
            fix.teachingSlots, fix.allRooms, fix.context);

        assertTrue(repaired, "a feasible swap must exist (move a single-period theory entry to the free cell)");
        List<TimetableEntry> entries = withLabs(fix.timetable);
        assertEquals(42, entries.size(),
            "the stranded 42nd period must be placed: " + dump(entries));

        assertEquals(5, theoryOf(entries, fix.missing), "missing subject must reach its exact 5 theory periods");
        assertEquals(2, labOf(entries, fix.missing), "missing subject labs untouched");

        assertNoClashes(entries);
        assertNoBreakSlot(entries);

        // The previously free cell was genuinely blocked FOR THE MISSING FACULTY —
        // the repair must never place the missing subject there.
        assertTrue(entries.stream()
            .filter(e -> "SAT".equals(e.getDayOfWeek()) && e.getTimeSlot().getSlotOrder() == 7)
            .noneMatch(e -> fix.missingFaculty.getId().equals(e.getFaculty().getId())),
            "missing subject's faculty is cross-timetable blocked at the former free cell");

        // Idempotence: a second repair has no free cell left to act on.
        assertFalse(engine.attemptSwapRepair(
            fix.timetable, fix.missing, List.of(fix.missingFaculty),
            fix.teachingSlots, fix.allRooms, fix.context),
            "nothing left to repair once the 42nd slot is filled");
    }

    @Test
    @Transactional
    void swapRepair_skipsCandidatesWhoseVacatedCellIsAlsoBlocked_commitsAtomically() {
        Fixture fix = seedStrandedLayout();
        TimeSlot order7 = fix.slotByOrder.get(7);
        fix.context.getFacultyOccupancy()
            .computeIfAbsent("SAT_" + order7.getId(), k -> new HashSet<>()).add(fix.missingFaculty.getId());

        // Candidate A's occupied cells are poisoned: the missing faculty ALSO
        // teaches there, so using candidate A as the vacated cell must fail.
        List<TimetableEntry> candidateA = fix.placed.get(fix.get("SAUX").getId());
        for (TimetableEntry e : candidateA) {
            fix.context.getFacultyOccupancy()
                .computeIfAbsent(e.getDayOfWeek() + "_" + e.getTimeSlot().getId(), k -> new HashSet<>())
                .add(fix.missingFaculty.getId());
        }
        Map<String, String> candidateABefore = cells(candidateA);

        boolean repaired = engine.attemptSwapRepair(
            fix.timetable, fix.missing, List.of(fix.missingFaculty),
            fix.teachingSlots, fix.allRooms, fix.context);

        assertTrue(repaired, "a non-poisoned candidate must still be found");
        List<TimetableEntry> entries = withLabs(fix.timetable);
        assertEquals(42, entries.size());
        assertEquals(5, theoryOf(entries, fix.missing));

        // Atomic restore guarantee: candidate A is untouched (a later candidate
        // was used instead), so the poisoned entries kept BOTH their cells and
        // their in-context registration.
        assertEquals(candidateABefore, cells(candidateA), "poisoned candidate must not be moved");

        // The 42nd entry belongs to the missing subject and sits on a vacated cell.
        List<TimetableEntry> newTheory = entriesOf(entries, fix.missing).stream()
            .filter(e -> !e.isLab()).toList();
        assertEquals(5, newTheory.size());
        assertNoClashes(entries);
    }

    @Test
    @Transactional
    void swapRepair_noLegalSwap_returnsFalse_withoutMutatingAnything() {
        Fixture fix = seedStrandedLayout();

        // Give EVERY movable entry to the missing subject's faculty: the
        // self-swap guard then rejects all candidates, so no legal swap exists.
        for (TimetableEntry e : fix.timetable.getEntries()) {
            if (e.isLab() || fix.missing.getId().equals(e.getSubject().getId())) {
                continue;
            }
            e.setFaculty(fix.missingFaculty);
        }
        ConstraintContext rebuilt = engine.buildContext(
            fix.timetable.getEntries(), fix.timetable, Map.of());
        rebuilt.getFacultyOccupancy()
            .computeIfAbsent("SAT_" + fix.slotByOrder.get(7).getId(), k -> new HashSet<>())
            .add(fix.missingFaculty.getId());

        String beforeCells = dump(fix.timetable.getEntries());
        int beforeSize = fix.timetable.getEntries().size();
        int beforeOcc = rebuilt.getSectionOccupancy().size();

        boolean repaired = engine.attemptSwapRepair(
            fix.timetable, fix.missing, List.of(fix.missingFaculty),
            fix.teachingSlots, fix.allRooms, rebuilt);

        assertFalse(repaired, "self-swap-only layout offers no legal repair");
        assertEquals(beforeSize, fix.timetable.getEntries().size(), "no entry may be added");
        assertEquals(beforeCells, dump(fix.timetable.getEntries()), "no entry may move");
        assertEquals(beforeOcc, rebuilt.getSectionOccupancy().size(), "context maps must stay consistent");
    }

    @Test
    @Transactional
    void fullGeneration_normalFortyTwoDemand_isUnaffectedBySwapRepair() {
        Department dept = newDepartment("SWF");
        Section section = dept.getAcademicYears().get(0).getSections().get(0);

        // Own classroom pool: with no competing timetable and no cross-timetable
        // room occupancy, the greedy engine must reach the exact 42/42 solve.
        int n = COUNTER.getAndIncrement();
        newRoom(dept, "SWF-T" + n, "LECTURE_HALL", 60);
        newRoom(dept, "SWF-T" + (n + 100), "LECTURE_HALL", 60);
        newRoom(dept, "SWF-L" + n, "LAB", 40);
        newRoom(dept, "SWF-L" + (n + 100), "LAB", 40);

        List<Subject> subjects = List.of(
            newSubject(dept, section, newFaculty(dept, "SWA"), "SWT1", "Core A", 7, 0, 1),
            newSubject(dept, section, newFaculty(dept, "SWB"), "SWT2", "Core B", 6, 0, 1),
            newSubject(dept, section, newFaculty(dept, "SWC"), "SWT3", "Core C", 6, 0, 1),
            newSubject(dept, section, newFaculty(dept, "SWD"), "SWT4", "Core D", 5, 0, 1),
            newSubject(dept, section, newFaculty(dept, "SWE"), "SWL1", "Lab Prog", 4, 2, 1),
            newSubject(dept, section, newFaculty(dept, "SWF"), "SWL2", "Lab Net", 3, 2, 1),
            newSubject(dept, section, newFaculty(dept, "SWG"), "SWL3", "Lab DB", 3, 2, 1),
            newSubject(dept, section, newFaculty(dept, "SWH"), "SWT5", "Core E", 2, 0, 1));

        Timetable timetable = newTimetable(dept, section);
        engine.generateSchedule(timetable);

        List<TimetableEntry> entries = timetable.getEntries();
        assertEquals(42, entries.size(),
            "42-demand curriculum must still fill all 42 slots: " + dump(entries));
        for (Subject s : subjects) {
            assertEquals(s.getTheoryHours().intValue(), theoryOf(entries, s),
                s.getSubjectCode() + " theory demand must be exact");
            assertEquals(s.getPracticalHours().intValue(), labOf(entries, s),
                s.getSubjectCode() + " lab demand must be exact");
        }
        assertNoClashes(entries);
        assertNoBreakSlot(entries);
        assertNoConflicts(timetable);
    }

    // ── strand fixture ───────────────────────────────────────────────────────

/**
     * Seeds a full 41-entry partial layout on a fresh department/section: all
     * 42 teaching cells except (SAT, order 7) are occupied exactly once. The
     * missing subject (SMIS, blockSize 1) has 4 of its 5 theory periods placed.
     */
    private Fixture seedStrandedLayout() {
        int n = COUNTER.getAndIncrement();
        Department dept = newDepartment("SWP");
        Section section = dept.getAcademicYears().get(0).getSections().get(0);

        Faculty fS = newFaculty(dept, "FSM");
        Faculty f1 = newFaculty(dept, "FSA");
        Faculty f2 = newFaculty(dept, "FSI");
        Faculty f3 = newFaculty(dept, "FSC");
        Faculty f4 = newFaculty(dept, "FSP");
        Faculty f5 = newFaculty(dept, "FS5");
        Faculty f6 = newFaculty(dept, "FS6");
        Faculty f7 = newFaculty(dept, "FS7");

        List<Subject> subjects = List.of(
            newSubject(dept, section, f1, "SAUX", "Algorithms", 5, 0, 1),
            newSubject(dept, section, f2, "SINV", "Networks", 5, 0, 1),
            newSubject(dept, section, f3, "SCLT", "Compiler", 6, 0, 2),
            newSubject(dept, section, f4, "SPHY", "Physics", 3, 0, 1),
            newSubject(dept, section, fS, "SMIS", "Missed", 5, 2, 1),
            newSubject(dept, section, f6, "SLB1", "Embed Lab", 2, 2, 1),
            newSubject(dept, section, f7, "SLB2", "IoT Lab", 2, 2, 1),
            newSubject(dept, section, f5, "SGGE", "Graphics", 6, 0, 1));

        List<Classroom> rooms = List.of(
            newRoom(dept, "SR" + n, "LECTURE_HALL", 60),
            newRoom(dept, "SR" + (n + 100), "LECTURE_HALL", 60),
            newRoom(dept, "SL" + n, "LAB", 40),
            newRoom(dept, "SL" + (n + 100), "LAB", 40));

        Timetable timetable = newTimetable(dept, section);
        Fixture fix = new Fixture(timetable, subjects, fS, rooms);
        fix.teachingSlots = teachingSlots();
        fix.slotByOrder = fix.teachingSlots.stream()
            .collect(Collectors.toMap(TimeSlot::getSlotOrder, Function.identity(), (a, b) -> a));

        // Theoretical demand to place (SMIS keeps one theory period for the repair).
        Map<Long, Integer> theoryBudget = new LinkedHashMap<>();
        theoryBudget.put(fix.get("SAUX").getId(), 5);
        theoryBudget.put(fix.get("SINV").getId(), 5);
        theoryBudget.put(fix.get("SCLT").getId(), 6);
        theoryBudget.put(fix.get("SPHY").getId(), 3);
        theoryBudget.put(fix.get("SMIS").getId(), 4);
        theoryBudget.put(fix.get("SLB1").getId(), 2);
        theoryBudget.put(fix.get("SLB2").getId(), 2);
        theoryBudget.put(fix.get("SGGE").getId(), 6);

        // Four 2-period consecutive lab blocks on distinct non-SAT days.
        Map<Long, Integer> labStartSlotOrder = new HashMap<>();
        labStartSlotOrder.put(fix.get("SMIS").getId(), 6); // FRI o6+o7
        labStartSlotOrder.put(fix.get("SGGE").getId(), 6); // WED o6+o7
        labStartSlotOrder.put(fix.get("SLB1").getId(), 2); // TUE o2+o3
        labStartSlotOrder.put(fix.get("SLB2").getId(), 2); // THU o2+o3

        fix.placed = seedPartialLayout(timetable, section, subjects,
            theoryBudget, labStartSlotOrder, List.of("FRI", "WED", "TUE", "THU"), rooms);
        assertEquals(41, timetable.getEntries().size(),
            "seed must leave exactly one free cell: " + dump(withLabs(timetable)));

        fix.context = engine.buildContext(timetable.getEntries(), timetable, Map.of());
        return fix;
    }

    /**
     * Builds the fixed 41-entry layout: labs first (fixed cells), then subject
     * theory in {@code subjects} order filling the day-major cell scan while
     * skipping occupied cells and the (SAT, order 7) free cell.
     *
     * @return subjectId → placed entries (in insertion order)
     */
    private Map<Long, List<TimetableEntry>> seedPartialLayout(Timetable timetable, Section section,
            List<Subject> subjects, Map<Long, Integer> theoryBudget,
            Map<Long, Integer> labStartSlotOrder, List<String> labDays, List<Classroom> rooms) {

        List<TimeSlot> slots = teachingSlots();
        Map<Integer, TimeSlot> byOrder = slots.stream()
            .collect(Collectors.toMap(TimeSlot::getSlotOrder, Function.identity(), (a, b) -> a));
        List<Classroom> theoryRooms = rooms.stream().filter(r -> !"LAB".equalsIgnoreCase(r.getRoomType())).toList();
        List<Classroom> labRooms = rooms.stream().filter(r -> "LAB".equalsIgnoreCase(r.getRoomType())).toList();

        Map<Long, List<TimetableEntry>> placed = new HashMap<>();
        for (Subject s : subjects) {
            placed.put(s.getId(), new ArrayList<>());
        }
        Set<String> occupied = new HashSet<>();

        // Labs: two consecutive non-break periods on the given day.
        int labDayIndex = 0;
        int labRoomIndex = 0;
        for (Subject s : subjects) {
            Integer startOrder = labStartSlotOrder.get(s.getId());
            if (startOrder == null) continue;
            String day = labDays.get(labDayIndex++);
            for (int j = 0; j < 2; j++) {
                TimetableEntry e = entryMapper.buildEntry(day, byOrder.get(startOrder + j), s,
                    s.getAssignedFaculty(), labRooms.get(labRoomIndex++ % labRooms.size()), section, true);
                timetable.addEntry(e);
                placed.get(s.getId()).add(e);
                occupied.add(day + "_" + (startOrder + j));
            }
        }

        // Theory: heyday-major scan, skipping occupied cells and (SAT, order 7).
        List<int[]> cells = new ArrayList<>();
        for (int d = 0; d < DAYS.size(); d++) {
            for (TimeSlot slot : slots) {
                if (d == 5 && slot.getSlotOrder() == 7) continue; // the one free cell
                cells.add(new int[]{d, slot.getSlotOrder()});
            }
        }
        int cursor = 0;
        for (Subject s : subjects) {
            int budget = theoryBudget.getOrDefault(s.getId(), 0);
            int roomIndex = 0;
            for (int k = 0; k < budget; k++) {
                while (occupied.contains(DAYS.get(cells.get(cursor)[0]) + "_" + cells.get(cursor)[1])) {
                    cursor++;
                }
                int[] cell = cells.get(cursor++);
                TimetableEntry e = entryMapper.buildEntry(DAYS.get(cell[0]), byOrder.get(cell[1]), s,
                    s.getAssignedFaculty(), theoryRooms.get(roomIndex++ % theoryRooms.size()), section, false);
                timetable.addEntry(e);
                placed.get(s.getId()).add(e);
                occupied.add(DAYS.get(cell[0]) + "_" + cell[1]);
            }
        }
        return placed;
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private Department newDepartment(String prefix) {
        int n = COUNTER.getAndIncrement();
        Department dept = Department.builder()
            .name(prefix + "-" + n)
            .hodName("Swap HOD")
            .contactEmail(prefix.toLowerCase() + n + "@local.test")
            .contactPhone("000")
            .building("Swap Block")
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
            .firstName("Swap")
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

    private Classroom newRoom(Department dept, String number, String type, int capacity) {
        return classroomRepository.saveAndFlush(Classroom.builder()
            .roomNumber(number)
            .roomName(number)
            .building("Swap Block")
            .department(dept)
            .roomType(type)
            .capacity(capacity)
            .status("AVAILABLE")
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

    private List<TimeSlot> teachingSlots() {
        return timeSlotRepository.findAllByOrderBySlotOrderAsc().stream()
            .filter(s -> !Boolean.TRUE.equals(s.getIsBreak()))
            .toList();
    }

    private static final class Fixture {
        final Timetable timetable;
        final List<Subject> subjects;
        final Subject missing;
        final Faculty missingFaculty;
        final List<Classroom> allRooms;
        List<TimeSlot> teachingSlots;
        Map<Integer, TimeSlot> slotByOrder;
        Map<Long, List<TimetableEntry>> placed;
        ConstraintContext context;

        Fixture(Timetable timetable, List<Subject> subjects, Faculty missingFaculty, List<Classroom> allRooms) {
            this.timetable = timetable;
            this.subjects = subjects;
            this.missing = subjects.stream()
                .filter(s -> s.getSubjectCode().startsWith("SMIS")).findFirst().orElseThrow();
            this.missingFaculty = missingFaculty;
            this.allRooms = allRooms;
        }

        Subject get(String codePrefix) {
            return subjects.stream().filter(s -> s.getSubjectCode().startsWith(codePrefix)).findFirst().orElseThrow();
        }
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

    private void assertNoConflicts(Timetable t) {
        List<TimetableConflict> hard = t.getConflicts().stream()
            .filter(c -> "HIGH".equalsIgnoreCase(c.getSeverity()))
            .toList();
        assertTrue(hard.isEmpty(), "unexpected HIGH conflicts on a feasible solve: " + hard);
        assertEquals(0, t.getConflicts().size(), "unexpected conflicts on the 42/42 solve: " + t.getConflicts());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<TimetableEntry> withLabs(Timetable t) {
        return t.getEntries();
    }

    private static List<TimetableEntry> entriesOf(List<TimetableEntry> all, Subject s) {
        return all.stream().filter(e -> e.getSubject() != null && e.getSubject().getId().equals(s.getId())).toList();
    }

    private static long theoryOf(List<TimetableEntry> all, Subject s) {
        return entriesOf(all, s).stream().filter(e -> !e.isLab()).count();
    }

    private static long labOf(List<TimetableEntry> all, Subject s) {
        return entriesOf(all, s).stream().filter(TimetableEntry::isLab).count();
    }

    private static Map<String, String> cells(List<TimetableEntry> entries) {
        Map<String, String> out = new LinkedHashMap<>();
        for (TimetableEntry e : entries) {
            out.put(e.getSubject().getSubjectCode() + "#" + System.identityHashCode(e),
                e.getDayOfWeek() + ":" + e.getTimeSlot().getSlotOrder());
        }
        return out;
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