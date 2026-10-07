package com.erp.timetable.module.timetable.benchmark;

import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 8 — Scenario A: single-section generation, Timefold engine, on the
 * real TT1 dataset. TT1 now ships with a LAB room (CS-LAB1), so the 6 practical
 * periods (CS471/CS785/CS691 × 2) are schedulable too: all 42 periods assigned
 * (36 theory + 6 practical) with hard score 0 and nothing unassigned.
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=timefold",
    "spring.datasource.url=jdbc:h2:mem:p8tt1_timefold;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class Phase8Tt1TimefoldBenchmark extends AbstractPhase8Tt1Benchmark {

    @Override
    protected String engineName() {
        return "timefold";
    }

    @Override
    protected int expectedTt1Entries() {
        return 42; // all 36 theory + 6 practical periods scheduled
    }

    @Override
    protected void assertScenarioInvariants(List<RunResult> measured) {
        for (RunResult r : measured) {
            assertEquals(42, r.assigned(), "Timefold TT1 must schedule all 42 periods");
            assertEquals(0, r.unassigned(), "TT1 with a LAB room must leave nothing unassigned");
            assertEquals(0, r.hard(), "the schedule must be feasible (hard score 0)");
            assertEquals(0, r.conflicts(), "Timefold TT1 must finish conflict-free");
            assertEquals(0, r.solverInfeasible(), "no SOLVER_INFEASIBLE conflict on TT1");
            assertEquals(0, r.roomClashes(), "no room double-booking");
            assertEquals(0, r.facultyClashes(), "no faculty double-booking");
            assertTrue(r.soft() <= 0, "soft score must be non-positive");
            assertTrue(r.solverMs() > 0, "Timefold must emit a solver-phase time");
            assertTrue(r.mappingMs() > 0, "Timefold must emit a mapping-phase time");
            assertTrue(r.applyMs() > 0, "Timefold must emit an apply-phase time");
        }
    }
}
