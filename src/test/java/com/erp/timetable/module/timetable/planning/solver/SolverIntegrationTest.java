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
import com.erp.timetable.module.timetable.planning.constraint.TimetableConstraintProvider;
import com.erp.timetable.module.timetable.planning.mapper.TimetablePlanningMapper;
import com.erp.timetable.module.timetable.planning.model.SchedulingSolution;
import com.erp.timetable.module.timetable.repository.TimetableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3C — Solver Integration Test.
 *
 * <p>Loads the TT1 dataset into a dedicated in-memory H2 database, maps it into
 * a {@link SchedulingSolution} via {@link TimetablePlanningMapper}, solves it
 * with the Timefold {@code SolverManager}, and verifies that the best solution
 * is feasible (hard score zero) with every lesson assigned and no room, faculty
 * or section conflicts. The same TT1 dataset is also regenerated with the
 * Greedy engine and the two outputs are compared.
 *
 * <p>Nothing is saved: the {@code @Transactional} test runs against the H2 dump
 * and rolls back; the solver operates purely on detached planning POJOs.
 * No REST endpoint is involved and {@code TimetableService} is untouched.
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
    "timetable.scheduler.engine=timefold",
    "spring.datasource.url=jdbc:h2:mem:tt1solver;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
@Transactional
class SolverIntegrationTest {

    /** TT1 dataset facts (from dataset/tt1-dump.sql). */
    private static final long TT1_TIMETABLE_ID = 1L;
    private static final int EXPECTED_LESSONS = 34;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TimetablePlanningMapper timetablePlanningMapper;

    @Autowired
    private TimetableRepository timetableRepository;

    @Autowired
    private ClassroomRepository classroomRepository;

    @Autowired
    private TimeSlotRepository timeSlotRepository;

    @Autowired
    private FacultyAvailabilityRepository facultyAvailabilityRepository;

    @Autowired
    private TimetableGeneratorEngine greedyEngine;

    @Autowired
    private SolverVerificationService solverVerificationService;

    @BeforeEach
    void loadTt1Dataset() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("RUNSCRIPT FROM 'classpath:dataset/tt1-dump.sql'");
    }

    @Test
    void tt1Dataset_solverFindsFeasibleSolutionAndComparesWithGreedy() {
        Timetable timetable = timetableRepository.findById(TT1_TIMETABLE_ID).orElseThrow();
        List<Classroom> rooms = classroomRepository.findAll();
        List<TimeSlot> timeSlots = timeSlotRepository.findAll();
        List<FacultyAvailability> availability = facultyAvailabilityRepository.findAll();

        // Solver problem: an in-memory snapshot of the TT1 dump.
        SchedulingSolution solverProblem = timetablePlanningMapper.toSolution(
            timetable, rooms, timeSlots, availability, SubjectDemandService.WORKING_DAYS);
        assertEquals(EXPECTED_LESSONS, solverProblem.getLessons().size(),
            "TT1 dump must map to the expected lesson demand");

        // Greedy reference run: regenerate the same timetable with the engine.
        greedyEngine.generateSchedule(timetable);
        SchedulingSolution greedyResult = timetablePlanningMapper.toSolution(
            timetable, rooms, timeSlots, availability, SubjectDemandService.WORKING_DAYS);

        SolverVerificationReport report = solverVerificationService.verify(solverProblem, greedyResult);

        assertNotNull(report, "verification report must be produced");
        assertEquals(TT1_TIMETABLE_ID, report.timetableId());
        assertEquals(EXPECTED_LESSONS, report.lessonCount());

        // Feasible solution.
        assertTrue(report.feasible(), "solver must reach a feasible solution: " + report.bestScore());
        assertEquals(0, report.bestScore().hardScore(), "hard score must be zero");

        // Every lesson assigned.
        assertEquals(EXPECTED_LESSONS, report.scheduledLessons(), "every lesson must be scheduled");
        assertEquals(0, report.unassignedLessons(), "no lesson may be left unassigned");

        // No room / faculty / section conflicts.
        assertEquals(0, report.integrity().roomConflicts(), "no room conflicts may remain");
        assertEquals(0, report.integrity().facultyConflicts(), "no faculty conflicts may remain");
        assertEquals(0, report.integrity().sectionConflicts(), "no section conflicts may remain");

        // Violation counts agree with the integrity check.
        assertEquals(0, report.hardViolationCount(), "no hard constraint violations may remain");
        assertFalse(report.constraintViolationBreakdown().containsKey(TimetableConstraintProvider.UNASSIGNED_LESSONS),
            "no lesson may be left unassigned: " + report.constraintViolationBreakdown());

        // Greedy comparison was produced and scored.
        assertNotNull(report.greedy(), "greedy comparison must be present");
        assertTrue(report.greedy().lessonsPlaced() >= 0);
        assertTrue(report.greedy().remainingFreePeriods() >= 0);
        assertNotNull(report.greedy().score(), "greedy must be scored with the identical constraint set");
    }

    @Test
    void tt1Dataset_solverBuildsFeasibleSolutionFromUnassigned() {
        Timetable timetable = timetableRepository.findById(TT1_TIMETABLE_ID).orElseThrow();
        List<Classroom> rooms = classroomRepository.findAll();
        List<TimeSlot> timeSlots = timeSlotRepository.findAll();
        List<FacultyAvailability> availability = facultyAvailabilityRepository.findAll();

        SchedulingSolution problem = timetablePlanningMapper.toSolution(
            timetable, rooms, timeSlots, availability, SubjectDemandService.WORKING_DAYS);
        // Start from a blank slate so the solver must construct the schedule.
        problem.getLessons().forEach(lesson -> {
            lesson.setRoom(null);
            lesson.setTimeSlot(null);
        });

        SolverVerificationReport report = solverVerificationService.verify(problem);

        assertTrue(report.feasible(), "solver must construct a feasible solution from unassigned");
        assertEquals(0, report.bestScore().hardScore());
        assertEquals(EXPECTED_LESSONS, report.scheduledLessons(), "all lessons must be scheduled");
        assertEquals(0, report.unassignedLessons(), "no lesson may be left unassigned");
        assertEquals(0, report.integrity().roomConflicts(), "no room conflicts may remain");
        assertEquals(0, report.integrity().facultyConflicts(), "no faculty conflicts may remain");
        assertEquals(0, report.integrity().sectionConflicts(), "no section conflicts may remain");
        assertEquals(0, report.hardViolationCount(), "no hard constraint violations may remain");
        assertFalse(report.constraintViolationBreakdown().containsKey(TimetableConstraintProvider.UNASSIGNED_LESSONS),
            "no lesson may be left unassigned: " + report.constraintViolationBreakdown());
        assertTrue(report.scoreCalculationCount() > 0, "solver must have searched the space");
    }
}
