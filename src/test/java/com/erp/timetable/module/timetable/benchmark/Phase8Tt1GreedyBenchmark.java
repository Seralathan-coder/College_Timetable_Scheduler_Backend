package com.erp.timetable.module.timetable.benchmark;

import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 8 — Scenario A: single-section generation, Greedy engine (default),
 * on the real TT1 dataset. Greedy has no separable solver phase, so solver /
 * mapping / apply / persist breakdowns are reported as not applicable. No
 * cross-timetable occupancy (Scenario B/C dimension) applies to single-section.
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=greedy",
    "spring.datasource.url=jdbc:h2:mem:p8tt1_greedy;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class Phase8Tt1GreedyBenchmark extends AbstractPhase8Tt1Benchmark {

    @Override
    protected String engineName() {
        return "greedy";
    }

    @Override
    protected int expectedTt1Entries() {
        return 42; // all 36 theory periods placed; 6 practical periods placed (LAB room added to the dataset)
    }

    @Override
    protected void assertScenarioInvariants(List<RunResult> measured) {
        for (RunResult r : measured) {
            assertEquals(42, r.assigned(), "Greedy TT1 must schedule all 42 periods (36 theory + 6 practical)");
            assertEquals(0, r.unassigned(), "TT1 with a LAB room must leave nothing unassigned");
            assertEquals(0, r.conflicts(), "TT1 records no conflicts when the LAB room is available");
            assertEquals(0, r.roomClashes(), "Greedy TT1 must have no room clashes");
            assertEquals(0, r.facultyClashes(), "Greedy TT1 must have no faculty clashes");
        }
    }
}
