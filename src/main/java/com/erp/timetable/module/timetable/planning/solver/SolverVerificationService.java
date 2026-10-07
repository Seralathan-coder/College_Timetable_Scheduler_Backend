package com.erp.timetable.module.timetable.planning.solver;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.SolverJob;
import ai.timefold.solver.core.api.solver.SolverManager;
import com.erp.timetable.module.timetable.planning.constraint.TimetableConstraintProvider;
import com.erp.timetable.module.timetable.planning.model.PlanningLesson;
import com.erp.timetable.module.timetable.planning.model.SchedulingSolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;

/**
 * Isolated Solver Verification module (Phase 3C).
 *
 * <p>Given an in-memory {@link SchedulingSolution}, solves it with Timefold via
 * {@link SolverManager}, then produces a {@link SolverVerificationReport}: solve
 * time, best score, feasibility, scheduled/unassigned counts, hard/soft
 * violation counts, the per-constraint violation breakdown and a direct
 * integrity check of the best solution (no room/faculty/section conflicts).
 *
 * <p>Since Phase 5 the planning variables are nullable: a lesson may be left
 * <em>unassigned</em> (null room and window) when no placement satisfies every
 * hard constraint. Such lessons are counted in {@code unassignedLessons} and
 * penalised only by the soft "Minimize unassigned lessons" constraint, so
 * feasibility (hard score zero) no longer implies every lesson is scheduled.
 *
 * <p>Scoring uses the open-source {@link ConstraintVerifier} API
 * ({@code givenSolution(...).getScore()}) — one aggregate pass plus one pass per
 * constraint — because Timefold's score-analysis / match-count API is a
 * commercial enterprise feature. The breakdown therefore reports per-constraint
 * <em>violation units</em> (the absolute penalty score) rather than match counts.
 *
 * <p>An optional Greedy-derived solution can be supplied to compare the two
 * engines on the same TT1 dataset; both are scored with the identical
 * {@code TimetableConstraintProvider}, so the hard/soft scores are comparable.
 *
 * <p>Nothing here writes to the database, exposes a REST endpoint, or touches
 * {@code TimetableService}. The service is registered together with the
 * {@code timefold} scheduling engine ({@code timetable.scheduler.engine=timefold})
 * and shares the exact same {@link SolverManager} / {@link SolverFactory} beans
 * (see {@link TimefoldSolverConfig}).
 */
@Component
@ConditionalOnProperty(name = "timetable.scheduler.engine", havingValue = "timefold")
public class SolverVerificationService {

    private static final Logger log = LoggerFactory.getLogger(SolverVerificationService.class);

    private static final Map<String, BiFunction<TimetableConstraintProvider, ConstraintFactory, Constraint>>
        CONSTRAINT_METHODS = buildConstraintMethods();

    private final SolverManager<SchedulingSolution> solverManager;
    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier;

    public SolverVerificationService(SolverManager<SchedulingSolution> solverManager,
            SolverFactory<SchedulingSolution> solverFactory) {
        this.solverManager = solverManager;
        this.constraintVerifier = ConstraintVerifier.build(
            new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);
    }

    public SolverVerificationReport verify(SchedulingSolution problem) {
        return verify(problem, null);
    }

    /**
     * Solves {@code problem} in memory and produces the verification report.
     *
     * @param problem      the TT1 {@link SchedulingSolution} (snapshot before any
     *                     Greedy regeneration)
     * @param greedyResult an optional solution mapped from the Greedy engine's
     *                     output on the same dataset, or {@code null}
     */
    public SolverVerificationReport verify(SchedulingSolution problem, SchedulingSolution greedyResult) {
        long heapBefore = usedHeapBytes();
        long wallStartNanos = System.nanoTime();

        SolverJob<SchedulingSolution> job = solverManager.solve(problem.getTimetableId(), problem);
        SchedulingSolution best = awaitBestSolution(job);

        Duration solveTime = job.getSolvingDuration();
        long wallTimeMillis = Duration.ofNanos(System.nanoTime() - wallStartNanos).toMillis();
        long scoreCalculationCount = job.getScoreCalculationCount();
        long memoryDeltaBytes = usedHeapBytes() - heapBefore;

        HardSoftScore bestScore = scoreOf(best);
        boolean feasible = bestScore.hardScore() == 0;
        Map<String, Integer> breakdown = constraintViolationBreakdown(best);

        int scheduled = countAssigned(best);
        SolverVerificationReport report = new SolverVerificationReport(
            problem.getTimetableId(),
            best.getLessons().size(),
            solveTime,
            scoreCalculationCount,
            bestScore,
            feasible,
            scheduled,
            best.getLessons().size() - scheduled,
            remainingFreePeriods(best),
            (int) -bestScore.hardScore(),
            (int) -bestScore.softScore(),
            breakdown,
            memoryDeltaBytes,
            integrityOf(best),
            greedyResult != null ? compareWithGreedy(greedyResult) : null);

        logReport(report, wallTimeMillis);
        return report;
    }

    /** Scores an arbitrary solution with the identical constraint set. */
    public HardSoftScore scoreOf(SchedulingSolution solution) {
        return constraintVerifier.verifyThat().givenSolution(solution).getScore();
    }

    /**
     * Per-constraint violation breakdown: constraint name -> absolute penalty
     * units ({@code -score}), only for constraints with a non-zero contribution.
     */
    public Map<String, Integer> constraintViolationBreakdown(SchedulingSolution solution) {
        Map<String, Integer> breakdown = new TreeMap<>();
        CONSTRAINT_METHODS.forEach((name, constraintMethod) -> {
            HardSoftScore score = constraintVerifier.verifyThat(constraintMethod)
                .givenSolution(solution)
                .getScore();
            if (score.hardScore() < 0) {
                breakdown.put(name, (int) -score.hardScore());
            } else if (score.softScore() < 0) {
                breakdown.put(name, (int) -score.softScore());
            }
        });
        return breakdown;
    }

    /** Greedy-side metrics: lessons placed, remaining free periods, score. */
    public SolverVerificationReport.GreedyComparison compareWithGreedy(SchedulingSolution greedySolution) {
        return new SolverVerificationReport.GreedyComparison(
            countAssigned(greedySolution),
            remainingFreePeriods(greedySolution),
            scoreOf(greedySolution));
    }

    private static Map<String, BiFunction<TimetableConstraintProvider, ConstraintFactory, Constraint>>
            buildConstraintMethods() {
        Map<String, BiFunction<TimetableConstraintProvider, ConstraintFactory, Constraint>> methods =
            new LinkedHashMap<>();
        methods.put(TimetableConstraintProvider.SECTION_CONFLICT, TimetableConstraintProvider::sectionConflict);
        methods.put(TimetableConstraintProvider.FACULTY_CONFLICT, TimetableConstraintProvider::facultyConflict);
        methods.put(TimetableConstraintProvider.ROOM_CONFLICT, TimetableConstraintProvider::roomConflict);
        methods.put(TimetableConstraintProvider.FACULTY_AVAILABILITY, TimetableConstraintProvider::facultyAvailability);
        methods.put(TimetableConstraintProvider.FACULTY_DAILY_HOURS, TimetableConstraintProvider::facultyDailyHoursLimit);
        methods.put(TimetableConstraintProvider.FACULTY_WEEKLY_HOURS, TimetableConstraintProvider::facultyWeeklyHoursLimit);
        methods.put(TimetableConstraintProvider.DEPARTMENT_PERMISSION, TimetableConstraintProvider::departmentPermission);
        methods.put(TimetableConstraintProvider.FACULTY_ASSIGNED_SUBJECT, TimetableConstraintProvider::facultyAssignedSubject);
        methods.put(TimetableConstraintProvider.ROOM_TYPE_MATCH, TimetableConstraintProvider::roomTypeMatch);
        methods.put(TimetableConstraintProvider.ROOM_CAPACITY, TimetableConstraintProvider::roomCapacity);
        methods.put(TimetableConstraintProvider.LAB_CONSECUTIVE_BLOCK, TimetableConstraintProvider::labConsecutiveBlock);
        methods.put(TimetableConstraintProvider.CONSECUTIVE_TEACHING_RULE, TimetableConstraintProvider::consecutiveTeachingRule);
        methods.put(TimetableConstraintProvider.CROSS_TIMETABLE_OCCUPANCY, TimetableConstraintProvider::crossTimetableOccupancy);
        methods.put(TimetableConstraintProvider.UNASSIGNED_LESSONS, TimetableConstraintProvider::unassignedLessons);
        methods.put(TimetableConstraintProvider.IDLE_GAP, TimetableConstraintProvider::idleGap);
        methods.put(TimetableConstraintProvider.SUBJECT_DISTRIBUTION, TimetableConstraintProvider::subjectDistribution);
        return methods;
    }

    private SchedulingSolution awaitBestSolution(SolverJob<SchedulingSolution> job) {
        try {
            return job.getFinalBestSolution();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Solver verification interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Solver verification failed", e.getCause());
        }
    }

    /** Direct conflict counts on the (best) solution: the four verifications. */
    private SolverVerificationReport.Integrity integrityOf(SchedulingSolution solution) {
        Map<String, Integer> roomKeys = new HashMap<>();
        Map<String, Integer> facultyKeys = new HashMap<>();
        Map<String, Integer> sectionKeys = new HashMap<>();
        for (PlanningLesson lesson : solution.getLessons()) {
            if (lesson.getRoom() == null || lesson.getTimeSlot() == null) {
                continue; // unassigned lessons never create phantom conflicts
            }
            String day = lesson.getTimeSlot().getDayOfWeek();
            String slot = String.valueOf(lesson.getTimeSlot().getTimeSlotId());
            roomKeys.merge(lesson.getRoom().getRoomId() + "|" + day + "|" + slot, 1, Integer::sum);
            if (lesson.getFaculty() != null) {
                facultyKeys.merge(lesson.getFaculty().getFacultyId() + "|" + day + "|" + slot, 1, Integer::sum);
            }
            if (lesson.getSectionId() != null) {
                sectionKeys.merge(lesson.getSectionId() + "|" + day + "|" + slot, 1, Integer::sum);
            }
        }
        return new SolverVerificationReport.Integrity(
            excessPairs(roomKeys), excessPairs(facultyKeys), excessPairs(sectionKeys));
    }

    private static int excessPairs(Map<String, Integer> keys) {
        return keys.values().stream().mapToInt(count -> Math.max(0, count - 1)).sum();
    }

    private static int countAssigned(SchedulingSolution solution) {
        return (int) solution.getLessons().stream()
            .filter(lesson -> lesson.getRoom() != null && lesson.getTimeSlot() != null)
            .count();
    }

    private static int remainingFreePeriods(SchedulingSolution solution) {
        long occupied = solution.getLessons().stream()
            .filter(lesson -> lesson.getTimeSlot() != null)
            .map(lesson -> lesson.getTimeSlot().getDayOfWeek() + "|" + lesson.getTimeSlot().getTimeSlotId())
            .distinct()
            .count();
        return solution.getTimeSlots().size() - (int) occupied;
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private void logReport(SolverVerificationReport report, long wallTimeMillis) {
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("═══════════════ Timefold Solver Verification Report ═══════════════");
        sb.append(System.lineSeparator());
        sb.append("timetable id           : ").append(report.timetableId());
        sb.append(System.lineSeparator());
        sb.append("lessons                : ").append(report.lessonCount());
        sb.append(System.lineSeparator());
        sb.append("solve time             : ").append(report.solveTime().toMillis()).append(" ms");
        sb.append(System.lineSeparator());
        sb.append("wall time              : ").append(wallTimeMillis).append(" ms");
        sb.append(System.lineSeparator());
        sb.append("score calculations     : ").append(report.scoreCalculationCount());
        sb.append(System.lineSeparator());
        sb.append("best score             : ").append(report.bestScore());
        sb.append(System.lineSeparator());
        sb.append("feasible               : ").append(report.feasible() ? "YES" : "NO");
        sb.append(System.lineSeparator());
        sb.append("scheduled lessons      : ").append(report.scheduledLessons());
        sb.append(System.lineSeparator());
        sb.append("unassigned lessons     : ").append(report.unassignedLessons());
        sb.append(System.lineSeparator());
        sb.append("remaining free periods : ").append(report.remainingFreePeriods());
        sb.append(System.lineSeparator());
        sb.append("hard violations        : ").append(report.hardViolationCount());
        sb.append(System.lineSeparator());
        sb.append("soft violations        : ").append(report.softViolationCount());
        sb.append(System.lineSeparator());
        sb.append("heap delta (approx)    : ").append(report.memoryDeltaBytes() / 1_000_000d).append(" MB");
        sb.append(System.lineSeparator());
        sb.append("room conflicts         : ").append(report.integrity().roomConflicts());
        sb.append(System.lineSeparator());
        sb.append("faculty conflicts      : ").append(report.integrity().facultyConflicts());
        sb.append(System.lineSeparator());
        sb.append("section conflicts      : ").append(report.integrity().sectionConflicts());
        sb.append(System.lineSeparator());
        if (report.constraintViolationBreakdown().isEmpty()) {
            sb.append("constraint breakdown   : (none — no violations)");
        } else {
            sb.append("constraint breakdown   : ").append(report.constraintViolationBreakdown());
        }
        sb.append(System.lineSeparator());
        if (report.greedy() != null) {
            SolverVerificationReport.GreedyComparison greedy = report.greedy();
            sb.append("── Greedy engine comparison (same TT1 dataset) ───────────────");
            sb.append(System.lineSeparator());
            sb.append("greedy lessons placed  : ").append(greedy.lessonsPlaced());
            sb.append(System.lineSeparator());
            sb.append("greedy free periods    : ").append(greedy.remainingFreePeriods());
            sb.append(System.lineSeparator());
            sb.append("greedy hard/soft score : ").append(greedy.score());
            sb.append(System.lineSeparator());
        }
        sb.append("══════════════════════════════════════════════════════════════════");
        log.info(sb.toString());
    }
}
