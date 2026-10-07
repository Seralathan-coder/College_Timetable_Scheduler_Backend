package com.erp.timetable.module.timetable.planning.solver;

import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicType;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchType;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.erp.timetable.module.timetable.planning.constraint.TimetableConstraintProvider;
import com.erp.timetable.module.timetable.planning.model.PlanningLesson;
import com.erp.timetable.module.timetable.planning.model.SchedulingSolution;

import java.time.Duration;

/**
 * Programmatic {@link SolverConfig} for the Timefold timetable solver.
 *
 * <p>Kept XML-free: the config is assembled in code so the solver depends on
 * nothing but the existing planning model and the Phase 3A/3B
 * {@link TimetableConstraintProvider}. The solver is scoped to a single in-memory
 * {@link SchedulingSolution} and never touches JPA, the database, or the REST
 * layer.
 *
 * <p>Algorithm: a {@code FIRST_FIT} construction heuristic followed by a
 * {@code LATE_ACCEPTANCE} local search. Termination is driven by the spent and
 * unimproved-spent limits — there is <em>no</em> {@code bestScoreFeasible} early
 * exit, because since the planning variables allow unassigned, the trivially
 * "feasible" solution that leaves every lesson unassigned (hard score zero,
 * negative soft score) would terminate the solver before it schedules anything.
 *
 * <p>"Feasible" therefore means the hard score is zero; the soft score may be
 * negative when lessons are left unassigned (each unassigned lesson costs one
 * soft point). The solver keeps searching until no placement improves the soft
 * score (all lessons that can be scheduled are scheduled).
 */
public final class SolverConfigFactory {

    /** Safety net when a feasible solution cannot be found. */
    public static final Duration DEFAULT_TERMINATION_SPENT_LIMIT = Duration.ofSeconds(30);

    /** Stop shortly after the best score stops improving (infeasible instances). */
    public static final Duration DEFAULT_UNIMPROVED_SPENT_LIMIT = Duration.ofSeconds(5);

    private SolverConfigFactory() {
    }

    public static SolverConfig buildSolverConfig() {
        return buildSolverConfig(DEFAULT_TERMINATION_SPENT_LIMIT);
    }

    public static SolverConfig buildSolverConfig(Duration spentLimit) {
        return new SolverConfig()
            .withSolutionClass(SchedulingSolution.class)
            .withEntityClasses(PlanningLesson.class)
            .withConstraintProviderClass(TimetableConstraintProvider.class)
            .withEnvironmentMode(EnvironmentMode.NO_ASSERT)
            .withPhases(
                new ConstructionHeuristicPhaseConfig()
                    .withConstructionHeuristicType(ConstructionHeuristicType.FIRST_FIT),
                new LocalSearchPhaseConfig()
                    .withLocalSearchType(LocalSearchType.LATE_ACCEPTANCE))
            .withTerminationConfig(new TerminationConfig()
                .withSpentLimit(spentLimit));
    }

    /**
     * Production-grade termination for the scheduling engine (Phase 4): the same
     * spent limit as {@link #buildSolverConfig()}, plus an unimproved spent
     * limit so the solver stops shortly after the last score improvement instead
     * of burning the full spent limit. No {@code bestScoreFeasible} early exit —
     * see the class javadoc for why.
     */
    public static SolverConfig buildHardenedSolverConfig(Duration spentLimit, Duration unimprovedSpentLimit) {
        return new SolverConfig()
            .withSolutionClass(SchedulingSolution.class)
            .withEntityClasses(PlanningLesson.class)
            .withConstraintProviderClass(TimetableConstraintProvider.class)
            .withEnvironmentMode(EnvironmentMode.NO_ASSERT)
            .withPhases(
                new ConstructionHeuristicPhaseConfig()
                    .withConstructionHeuristicType(ConstructionHeuristicType.FIRST_FIT),
                new LocalSearchPhaseConfig()
                    .withLocalSearchType(LocalSearchType.LATE_ACCEPTANCE))
            .withTerminationConfig(new TerminationConfig()
                .withSpentLimit(spentLimit)
                .withUnimprovedSpentLimit(unimprovedSpentLimit));
    }
}
