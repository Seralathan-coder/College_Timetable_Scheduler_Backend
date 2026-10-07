package com.erp.timetable.module.timetable.planning.solver;

import ai.timefold.solver.core.api.score.HardSoftScore;

import java.time.Duration;
import java.util.Map;

/**
 * Immutable result of a single in-memory solver verification run (Phase 3C).
 *
 * <p>Captures everything the Solver Verification Report requires: solve time,
 * best score, feasibility, scheduled/unassigned lesson counts, hard/soft
 * violation counts, the per-constraint violation breakdown, an integrity check of
 * the best solution (room/faculty/section conflicts) and — when a Greedy result
 * is supplied — a {@link GreedyComparison} on the same TT1 dataset.
 */
public record SolverVerificationReport(
        Long timetableId,
        int lessonCount,
        Duration solveTime,
        long scoreCalculationCount,
        HardSoftScore bestScore,
        boolean feasible,
        int scheduledLessons,
        int unassignedLessons,
        int remainingFreePeriods,
        int hardViolationCount,
        int softViolationCount,
        Map<String, Integer> constraintViolationBreakdown,
        long memoryDeltaBytes,
        Integrity integrity,
        GreedyComparison greedy) {

    /** Conflict counts computed directly from the best solution. */
    public record Integrity(int roomConflicts, int facultyConflicts, int sectionConflicts) {
    }

    /** Greedy engine metrics scored with the identical constraint set. */
    public record GreedyComparison(int lessonsPlaced, int remainingFreePeriods, HardSoftScore score) {
    }
}
