package com.erp.timetable.module.timetable.planning.solver;

import com.erp.timetable.module.availability.entity.FacultyAvailability;
import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.availability.repository.FacultyAvailabilityRepository;
import com.erp.timetable.module.availability.repository.TimeSlotRepository;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.timetable.engine.TimetableGeneratorEngine;
import com.erp.timetable.module.timetable.engine.shared.SubjectDemandService;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import com.erp.timetable.module.timetable.planning.constraint.TimetableConstraintProvider;
import com.erp.timetable.module.timetable.planning.mapper.SolverResultMapper;
import com.erp.timetable.module.timetable.planning.mapper.TimetablePlanningMapper;
import com.erp.timetable.module.timetable.planning.model.AvailabilityFact;
import com.erp.timetable.module.timetable.planning.model.PlannableFaculty;
import com.erp.timetable.module.timetable.planning.model.PlannableRoom;
import com.erp.timetable.module.timetable.planning.model.PlannableSubject;
import com.erp.timetable.module.timetable.planning.model.PlannableTimeSlot;
import com.erp.timetable.module.timetable.planning.model.PlanningLesson;
import com.erp.timetable.module.timetable.planning.model.SchedulingSolution;
import com.erp.timetable.module.timetable.repository.TimetableRepository;
import ai.timefold.solver.core.api.solver.SolverJob;
import ai.timefold.solver.core.api.solver.SolverManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import static com.erp.timetable.module.timetable.planning.constraint.TimetableConstraintProvider.UNASSIGNED_LESSON_WEIGHT;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.availabilityFact;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.faculty;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.room;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.subject;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.window;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 — unassigned ({@code allowsUnassigned}) planning-lesson semantics.
 *
 * <p>Since Phase 5 both planning variables of a {@link PlanningLesson} may stay
 * null. A lesson is <em>unassigned</em> when it has no room or no (day, time
 * slot) window; it then carries only the soft "Minimize unassigned lessons"
 * penalty (UNASSIGNED_LESSON_WEIGHT = 1000 soft points) and never a hard one.
 * Since Phase 6B the total soft score also includes the "Minimize faculty idle
 * gaps" penalty, so the scenarios assert unassigned counts (and, in the
 * single-constraint scenarios, the weighted soft penalty = 1000 × unassigned)
 * to stay focused on the unassigned semantics. This test verifies the eight
 * behaviours the feature promises:
 * <ol>
 *   <li>every schedulable lesson is assigned — 0 unassigned / 0 hard;</li>
 *   <li>a lab without any LAB room is left unassigned — no room-type violation,
 *       0 hard;</li>
 *   <li>a lesson whose faculty is blocked everywhere is left unassigned — no
 *       availability violation;</li>
 *   <li>multiple unavailable resources produce exactly the matching unassigned
 *       count;</li>
 *   <li>when a valid placement exists the solver prefers assigning — best soft
 *       score is greedily 0;</li>
 *   <li>unassigned lessons are never silently deleted from the best
 *       solution;</li>
 *   <li>{@link SolverResultMapper} preserves the unassigned state without
 *       clearing rows or creating phantom entries;</li>
 *   <li>the Greedy engine is untouched by the nullable planning model.</li>
 * </ol>
 *
 * <p>Scenarios 1, 7 and 8 run on the TT1 dump; the remaining scenarios solve
 * small hand-built {@link SchedulingSolution} instances. Nothing is persisted:
 * the {@code @Transactional} tests roll back and the solver works on detached
 * planning POJOs.
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
    "timetable.scheduler.engine=timefold",
    "spring.datasource.url=jdbc:h2:mem:tt1unassigned;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
@Transactional
class PlanningLessonUnassignedTest {

    /** TT1 dataset facts (from dataset/tt1-dump.sql). */
    private static final long TT1_TIMETABLE_ID = 1L;
    private static final int EXPECTED_LESSONS = 34;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TimetablePlanningMapper timetablePlanningMapper;

    @Autowired
    private SolverResultMapper solverResultMapper;

    @Autowired
    private TimetableRepository timetableRepository;

    @Autowired
    private ClassroomRepository classroomRepository;

    @Autowired
    private TimeSlotRepository timeSlotRepository;

    @Autowired
    private FacultyAvailabilityRepository facultyAvailabilityRepository;

    @Autowired
    private SolverVerificationService solverVerificationService;

    @Autowired
    private SolverManager<SchedulingSolution> solverManager;

    @Autowired
    private TimetableGeneratorEngine greedyEngine;

    @BeforeEach
    void loadTt1Dataset() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("RUNSCRIPT FROM 'classpath:dataset/tt1-dump.sql'");
    }

    // ── Scenario 1 — everything schedulable → 0 unassigned / 0 hard ────────

    @Test
    void allSchedulable_solverSchedulesEverything_withZeroUnassignedAndZeroHard() {
        SchedulingSolution problem = tt1Solution();
        // Start from a blank slate so the solver must construct the schedule.
        problem.getLessons().forEach(lesson -> {
            lesson.setRoom(null);
            lesson.setTimeSlot(null);
        });

        SolverVerificationReport report = solverVerificationService.verify(problem);

        assertEquals(0, report.bestScore().hardScore(), "hard score must be zero");
        assertEquals(0, report.unassignedLessons(), "no lesson may be left unassigned");
        assertEquals(EXPECTED_LESSONS, report.scheduledLessons(), "every schedulable lesson must be placed");
        assertFalse(report.constraintViolationBreakdown().containsKey(TimetableConstraintProvider.UNASSIGNED_LESSONS),
            "no lesson may be left unassigned: " + report.constraintViolationBreakdown());
    }

    // ── Scenario 2 — no LAB room → lab unassigned, no room-type violation ───

    @Test
    void labWithoutLabRoom_isLeftUnassigned_withNoRoomTypeViolation() {
        PlannableFaculty professor = faculty(1);
        PlannableSubject lab = subject(1, "CS205L", "LAB", 1L);

        SchedulingSolution problem = solutionWith(1L,
            List.of(labLesson(1, 1, professor, lab),
                labLesson(2, 1, professor, lab),
                labLesson(3, 1, professor, lab)),
            List.of(room(10)),                       // LECTURE_HALL only — no LAB room
            List.of(window(10, "MON", 1), window(11, "MON", 2), window(12, "MON", 3)),
            List.of());

        SolverVerificationReport report = solverVerificationService.verify(problem);

        assertEquals(0, report.bestScore().hardScore(), "no hard violation may remain");
        assertEquals(3, report.unassignedLessons(), "all three lab periods must be left unassigned");
        assertEquals(3 * UNASSIGNED_LESSON_WEIGHT, report.softViolationCount(),
            "each unassigned lesson costs UNASSIGNED_LESSON_WEIGHT soft points");
        assertFalse(report.constraintViolationBreakdown().containsKey(TimetableConstraintProvider.ROOM_TYPE_MATCH),
            "a lab without a LAB room must be unassigned, not a room-type violation");
    }

    // ── Scenario 3 — faculty blocked everywhere → unassigned, no violation ──

    @Test
    void facultyBlockedEverywhere_lessonIsLeftUnassigned_withNoAvailabilityViolation() {
        PlannableFaculty professor = faculty(1);
        PlannableSubject theory = subject(1, "CS101", "THEORY", 1L);

        SchedulingSolution problem = solutionWith(1L,
            List.of(theoryLesson(1, 1, professor, theory, null, null)),
            List.of(room(10)),
            List.of(window(10, "MON", 1), window(11, "MON", 2)),
            List.of(availabilityFact(1, "MON", 10, "BLOCKED"),
                availabilityFact(1, "MON", 11, "BLOCKED")));

        SolverVerificationReport report = solverVerificationService.verify(problem);

        assertEquals(0, report.bestScore().hardScore(), "no hard violation may remain");
        assertEquals(1, report.unassignedLessons(), "the only lesson must be left unassigned");
        assertEquals(UNASSIGNED_LESSON_WEIGHT, report.softViolationCount(),
            "each unassigned lesson costs UNASSIGNED_LESSON_WEIGHT soft points");
        assertFalse(report.constraintViolationBreakdown().containsKey(TimetableConstraintProvider.FACULTY_AVAILABILITY),
            "blocked placements must be avoided, not violated");
    }

    // ── Scenario 4 — multiple unavailable resources → correct unassigned count ──

    @Test
    void multipleUnavailableResources_unassignedCountMatchesDemand() {
        PlannableFaculty profTheory = faculty(1);
        PlannableSubject theory = subject(1, "CS101", "THEORY", 1L);
        PlannableFaculty profLab = faculty(2);
        PlannableSubject lab = subject(2, "CS205L", "LAB", 2L);

        SchedulingSolution problem = solutionWith(1L,
            List.of(
                theoryLesson(1, 1, profTheory, theory, null, null),
                theoryLesson(2, 2, profTheory, theory, null, null),
                labLesson(3, 1, profLab, lab),
                labLesson(4, 1, profLab, lab),
                labLesson(5, 1, profLab, lab)),
            List.of(room(10)),
            List.of(window(10, "MON", 1), window(11, "MON", 2)),
            List.of(availabilityFact(1, "MON", 10, "BLOCKED"),
                availabilityFact(1, "MON", 11, "BLOCKED")));

        SolverVerificationReport report = solverVerificationService.verify(problem);

        assertEquals(0, report.bestScore().hardScore(), "no hard violation may remain");
        assertEquals(5, report.unassignedLessons(),
            "2 blocked theory lessons + 3 lab lessons without a LAB room must all stay unassigned");
        assertEquals(5 * UNASSIGNED_LESSON_WEIGHT, report.softViolationCount(),
            "each unassigned lesson costs UNASSIGNED_LESSON_WEIGHT soft points");
    }

    // ── Scenario 5 — valid placement exists → solver prefers assigning ──────

    @Test
    void validPlacementExists_solverPrefersAssigning_softScoreZero() {
        PlannableFaculty professor = faculty(1);

        SchedulingSolution problem = solutionWith(1L,
            List.of(
                // One subject per lesson so the soft subject-distribution
                // objective (Phase 6C) stays silent and the soft score is
                // entirely determined by the unassigned penalty.
                theoryLesson(1, 1, professor, subject(1, "CS101", "THEORY", 1L), null, null),
                theoryLesson(2, 2, professor, subject(2, "CS102", "THEORY", 1L), null, null),
                theoryLesson(3, 3, professor, subject(3, "CS103", "THEORY", 1L), null, null)),
            List.of(room(10)),
            List.of(window(10, "MON", 1), window(11, "TUE", 1), window(12, "WED", 1)),
            List.of());

        SolverVerificationReport report = solverVerificationService.verify(problem);

        assertEquals(0, report.bestScore().hardScore(), "hard score must be zero");
        assertEquals(0, report.bestScore().softScore(),
            "the soft unassigned penalty must drive the solver to schedule everything");
        assertEquals(0, report.unassignedLessons(), "no lesson may be left unassigned");
        assertEquals(3, report.scheduledLessons(), "all three lessons must be placed");
    }

    // ── Scenario 6 — unassigned lessons are never silently deleted ──────────

    @Test
    void unassignedLessons_areNotSilentlyDroppedFromTheBestSolution() {
        PlannableFaculty professor = faculty(1);
        PlannableSubject lab = subject(1, "CS205L", "LAB", 1L);

        SchedulingSolution problem = solutionWith(1L,
            List.of(labLesson(1, 1, professor, lab),
                labLesson(2, 1, professor, lab),
                labLesson(3, 1, professor, lab)),
            List.of(room(10)),
            List.of(window(10, "MON", 1), window(11, "MON", 2), window(12, "MON", 3)),
            List.of());

        SchedulingSolution best = solveBest(problem);

        assertEquals(problem.getLessons().size(), best.getLessons().size(),
            "the best solution must keep every lesson — nothing is silently deleted");
        assertEquals(3, best.getLessons().stream()
                .filter(PlanningLessonUnassignedTest::isUnassigned).count(),
            "the unassigned lessons must remain observable in the best solution");
    }

    // ── Scenario 7 — SolverResultMapper preserves the unassigned state ──────

    @Test
    void solverResultMapper_preservesUnassignedState_withoutClearingRowsOrCreatingPhantoms() {
        Timetable timetable = timetableRepository.findById(TT1_TIMETABLE_ID).orElseThrow();
        SchedulingSolution solution = tt1Solution();

        // Simulate a solver outcome: two source-entry lessons left unassigned and
        // one fresh curriculum lesson that was never placed.
        PlanningLesson first = solution.getLessons().get(0);
        PlanningLesson second = solution.getLessons().get(1);
        Long firstEntryId = first.getSourceEntryId();
        Long secondEntryId = second.getSourceEntryId();
        first.setRoom(null);
        first.setTimeSlot(null);
        second.setRoom(null);
        second.setTimeSlot(null);
        solution.getLessons().add(labLesson(999, 1, faculty(2), subject(2, "CS999L", "LAB", 2L)));

        Map<Long, TimetableEntry> entriesById = new HashMap<>();
        timetable.getEntries().forEach(e -> entriesById.put(e.getId(), e));
        TimetableEntry firstEntry = entriesById.get(firstEntryId);
        TimetableEntry secondEntry = entriesById.get(secondEntryId);
        int entryCountBefore = timetable.getEntries().size();

        solverResultMapper.applyToTimetable(timetable, solution);

        assertEquals(entryCountBefore, timetable.getEntries().size(),
            "no silent deletion of source rows and no phantom rows for unassigned lessons");
        assertEquals(firstEntry.getClassroom().getId(), entriesById.get(firstEntryId).getClassroom().getId(),
            "an unassigned source-entry lesson must leave its existing row untouched");
        assertEquals(secondEntry.getClassroom().getId(), entriesById.get(secondEntryId).getClassroom().getId(),
            "an unassigned source-entry lesson must leave its existing row untouched");
        assertEquals(firstEntry.getDayOfWeek(), entriesById.get(firstEntryId).getDayOfWeek(),
            "an unassigned source-entry lesson must not have its day cleared");
        assertEquals(firstEntry.getTimeSlot().getId(), entriesById.get(firstEntryId).getTimeSlot().getId(),
            "an unassigned source-entry lesson must not have its slot cleared");
        assertTrue(timetable.getEntries().stream().noneMatch(e ->
                e.getClassroom() == null || e.getDayOfWeek() == null || e.getTimeSlot() == null),
            "the NOT NULL placement columns must be preserved on every row");
    }

    // ── Scenario 8 — Greedy engine is untouched by the nullable model ───────

    @Test
    void greedyEngine_isUntouchedByTheNullablePlanningModel() {
        Timetable timetable = timetableRepository.findById(TT1_TIMETABLE_ID).orElseThrow();

        greedyEngine.generateSchedule(timetable);

        assertEquals(42, timetable.getEntries().size(),
            "Greedy baseline on TT1: all 36 theory periods placed plus all 6 practical periods in the LAB room");
        assertTrue(timetable.getEntries().stream().noneMatch(e ->
                e.getClassroom() == null || e.getDayOfWeek() == null || e.getTimeSlot() == null),
            "every Greedy entry must keep a full placement");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private SchedulingSolution tt1Solution() {
        Timetable timetable = timetableRepository.findById(TT1_TIMETABLE_ID).orElseThrow();
        return timetablePlanningMapper.toSolution(
            timetable,
            classroomRepository.findAll(),
            timeSlotRepository.findAll(),
            facultyAvailabilityRepository.findAll(),
            SubjectDemandService.WORKING_DAYS);
    }

    private SchedulingSolution solveBest(SchedulingSolution problem) {
        SolverJob<SchedulingSolution> job = solverManager.solve(problem.getTimetableId(), problem);
        try {
            return job.getFinalBestSolution();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unassigned-semantics solve interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Unassigned-semantics solve failed", e.getCause());
        }
    }

    private static SchedulingSolution solutionWith(long timetableId, List<PlanningLesson> lessons,
            List<PlannableRoom> rooms, List<PlannableTimeSlot> windows, List<AvailabilityFact> facts) {
        return SchedulingSolution.builder()
            .timetableId(timetableId)
            .lessons(new ArrayList<>(lessons))
            .subjects(lessons.stream().map(PlanningLesson::getSubject).filter(Objects::nonNull).distinct().toList())
            .faculty(lessons.stream().map(PlanningLesson::getFaculty).filter(Objects::nonNull).distinct().toList())
            .rooms(new ArrayList<>(rooms))
            .timeSlots(new ArrayList<>(windows))
            .availabilityFacts(facts == null ? new ArrayList<>() : new ArrayList<>(facts))
            .build();
    }

    private static PlanningLesson theoryLesson(long id, long sectionId, PlannableFaculty professor,
            PlannableSubject subject, PlannableRoom room, PlannableTimeSlot timeSlot) {
        return PlanningLesson.builder()
            .id(id)
            .sectionId(sectionId)
            .subject(subject)
            .faculty(professor)
            .departmentId(professor != null ? professor.getDepartmentId() : null)
            .requiredCapacity(40)
            .room(room)
            .timeSlot(timeSlot)
            .isLab(false)
            .locked(false)
            .build();
    }

    private static PlanningLesson labLesson(long id, long sectionId, PlannableFaculty professor,
            PlannableSubject subject) {
        return PlanningLesson.builder()
            .id(id)
            .sectionId(sectionId)
            .subject(subject)
            .faculty(professor)
            .departmentId(professor != null ? professor.getDepartmentId() : null)
            .requiredCapacity(40)
            .isLab(true)
            .locked(false)
            .build();
    }

    private static boolean isUnassigned(PlanningLesson lesson) {
        return lesson.getRoom() == null || lesson.getTimeSlot() == null;
    }
}
