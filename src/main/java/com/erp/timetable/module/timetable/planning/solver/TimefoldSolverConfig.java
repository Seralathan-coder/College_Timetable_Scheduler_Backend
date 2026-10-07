package com.erp.timetable.module.timetable.planning.solver;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.SolverManager;
import com.erp.timetable.module.timetable.planning.model.SchedulingSolution;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Spring configuration for the Timefold {@link SolverFactory}, {@link SolverManager}
 * and {@link SolutionManager} (Phase 4 — the {@code timefold} scheduling engine).
 *
 * <p>The beans are registered only when {@code timetable.scheduler.engine} is
 * {@code timefold}; under the default {@code greedy} engine the Timefold solver
 * stack is not loaded at all. The shared {@link SolverConfigFactory} is used so
 * exactly one solver is built for both the scheduling engine and the Phase 3C
 * {@link SolverVerificationService}.
 *
 * <p>Termination uses the hardened config (see
 * {@link SolverConfigFactory#buildHardenedSolverConfig}): feasible-early-exit plus
 * spent and unimproved limits. The {@code timefold} engine additionally overrides
 * termination per solve via {@link SolverConfigOverride} so the spent/unimproved
 * budgets scale with the problem size, bounded by the same properties.
 */
@Configuration
@ConditionalOnProperty(name = "timetable.scheduler.engine", havingValue = "timefold")
public class TimefoldSolverConfig {

    private final Duration terminationSpentLimit;
    private final Duration terminationUnimprovedLimit;

    public TimefoldSolverConfig(
            @Value("${timetable.solver.termination.seconds:30}") int terminationSeconds,
            @Value("${timetable.solver.termination.unimproved-seconds:5}") int unimprovedSeconds) {
        this.terminationSpentLimit = Duration.ofSeconds(terminationSeconds);
        this.terminationUnimprovedLimit = Duration.ofSeconds(unimprovedSeconds);
    }

    @Bean
    public SolverFactory<SchedulingSolution> solverFactory() {
        return SolverFactory.create(SolverConfigFactory.buildHardenedSolverConfig(
            terminationSpentLimit, terminationUnimprovedLimit));
    }

    @Bean
    public SolverManager<SchedulingSolution> solverManager(SolverFactory<SchedulingSolution> solverFactory) {
        return SolverManager.create(solverFactory);
    }

    @Bean
    public SolutionManager<SchedulingSolution, HardSoftScore> solutionManager(
            SolverFactory<SchedulingSolution> solverFactory) {
        return SolutionManager.create(solverFactory);
    }
}
