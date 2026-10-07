package com.erp.timetable.module.timetable.benchmark;

import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 8 — Scenario B: multi-section generation, Timefold engine, on the
 * seeded CSE master data. Section B is generated while section A's timetable
 * exists; the Phase 7 cross-timetable occupancy hard constraint must keep every
 * B lesson off A's (faculty|room, day, slot) windows. The harness re-counts the
 * clashes independently and also reports the occupancy-fact input size.
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=timefold",
    "spring.datasource.url=jdbc:h2:mem:p8ms_timefold;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class Phase8MultiSectionTimefoldBenchmark extends AbstractPhase8MultiSectionBenchmark {

    @Override
    protected String engineName() {
        return "timefold";
    }

    @Override
    protected int expectedTt1Entries() {
        return 9; // unused on the seeded dataset, kept for the abstract contract
    }

    @Override
    protected void assertScenarioInvariants(List<RunResult> measured) {
        for (RunResult r : measured) {
            assertEquals(9, r.assigned(), "Timefold section B must schedule all 9 theory periods");
            assertEquals(0, r.unassigned(), "Timefold section B must leave nothing unassigned");
            assertEquals(0, r.hard(), "section B must solve with a zero hard score");
            assertEquals(0, r.crossTimetableClashes(),
                "section B must not reuse any (faculty|room, day, slot) window of section A");
            assertEquals(0, r.conflicts(), "section B must be conflict-free");
            assertTrue(r.occupancyFacts() >= 9,
                "section B must load section A's entries as occupancy facts, saw " + r.occupancyFacts());
        }
    }
}
