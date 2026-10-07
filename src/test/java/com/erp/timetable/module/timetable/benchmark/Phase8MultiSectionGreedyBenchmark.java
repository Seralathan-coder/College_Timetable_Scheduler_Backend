package com.erp.timetable.module.timetable.benchmark;

import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 8 — Scenario B: multi-section generation, Greedy engine (default), on
 * the seeded CSE master data. Greedy pre-loads existing entries into its clash
 * context (faculty/room/section), so B must not reuse any window A occupies;
 * the harness re-counts those cross-timetable clashes independently. Greedy has
 * no occupancy-fact concept, so occupancyFacts is not applicable.
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=greedy",
    "spring.datasource.url=jdbc:h2:mem:p8ms_greedy;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class Phase8MultiSectionGreedyBenchmark extends AbstractPhase8MultiSectionBenchmark {

    @Override
    protected String engineName() {
        return "greedy";
    }

    @Override
    protected int expectedTt1Entries() {
        return 9; // unused on the seeded dataset, kept for the abstract contract
    }

    @Override
    protected void assertScenarioInvariants(List<RunResult> measured) {
        for (RunResult r : measured) {
            assertEquals(9, r.assigned(), "Greedy section B must schedule all 9 theory periods");
            assertEquals(0, r.crossTimetableClashes(),
                "Greedy must not reuse a window occupied by section A");
            assertEquals(0, r.roomClashes(), "no room double-booking inside B");
            assertEquals(0, r.facultyClashes(), "no faculty double-booking inside B");
        }
    }
}
