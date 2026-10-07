package com.erp.timetable.module.timetable.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

/**
 * Phase 8 — Scenario C: locked-entry regeneration, Timefold-only, on the seeded
 * two-section CSE environment. Section A is generated, one of B's entries is
 * locked, then {@code regenerate-unlocked} re-places B's remaining demand while
 * the locked placement must survive unchanged and A must stay untouched and
 * clash-free. Measures the regeneration call itself.
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=timefold",
    "spring.datasource.url=jdbc:h2:mem:p8regen_timefold;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class Phase8RegenerationTimefoldBenchmark extends AbstractPhase8MultiSectionBenchmark {

    private static final String SESSION = "2025-2026 EVEN";
    private static final int B_REQUESTED = 9;

    @Override
    protected String scenarioName() {
        return "C";
    }

    @Override
    protected String engineName() {
        return "timefold";
    }

    @Override
    protected int expectedTt1Entries() {
        return 9; // unused on the seeded dataset, kept for the abstract contract
    }

    @Override
    protected RunResult runScenarioOnce(CapturedOutput output) throws Exception {
        Setup setup = prepareSectionsAndSubjects();

        // Section A first (the occupancy baseline).
        JsonNode first = postGenerateExpectSuccess(setup.cseId(), setup.secA().getId(), 3, SESSION);

        // Section B with one entry locked before the partial regeneration.
        long startGen = System.nanoTime();
        JsonNode second = postGenerateExpectSuccess(setup.cseId(), setup.secB().getId(), 3, SESSION);
        long setupApiMs = Duration.ofNanos(System.nanoTime() - startGen).toMillis();
        long secondId = second.get("id").asLong();

        long lockedEntryId = second.get("entries").get(0).get("id").asLong();
        lockEntry(lockedEntryId);

        String beforeRegen = output.getAll();
        long startRegen = System.nanoTime();
        JsonNode regenerated = regenerateUnlocked(secondId);
        long apiMs = Duration.ofNanos(System.nanoTime() - startRegen).toMillis();
        String logsRegen = logsSince(output, beforeRegen);

        int assigned = regenerated.get("entries").size();
        int unassigned = B_REQUESTED - assigned;

        int[] hs = parseHardSoft(logsRegen);
        int hard = hs != null ? hs[0] : N_A;
        int soft = hs != null ? hs[1] : N_A;

        int conflicts = regenerated.get("conflictCount").asInt();
        int solverInfeasible = countSolverInfeasible(regenerated);

        ClashCounts clashes = countClashes(regenerated.get("entries"));

        // Cross-timetable: regenerated B must still avoid A's windows.
        int crossTimetable = countCrossTimetableClashes(regenerated.get("entries"), first.get("entries"));

        long[] timings = parseEngineTimings(logsRegen);
        long engineMs = timings[0];
        long persistMs = engineMs > 0 ? Math.max(0L, apiMs - engineMs) : N_A;

        return new RunResult(B_REQUESTED, assigned, unassigned, hard, soft, conflicts, solverInfeasible,
            clashes.faculty(), clashes.room(), clashes.section(), crossTimetable,
            apiMs, setupApiMs, timings[1], timings[2], timings[3], persistMs,
            countOtherTimetableEntries(secondId),
            regenerated.get("optimizationScore").asInt());
    }

    @Override
    protected void assertScenarioInvariants(java.util.List<RunResult> measured) {
        for (RunResult r : measured) {
            org.junit.jupiter.api.Assertions.assertEquals(9, r.assigned(),
                "regeneration must re-schedule all 9 of B's theory periods");
            org.junit.jupiter.api.Assertions.assertEquals(0, r.hard(),
                "regeneration must keep a zero hard score");
            org.junit.jupiter.api.Assertions.assertEquals(0, r.roomClashes(),
                "regeneration must leave no room double-booking");
            org.junit.jupiter.api.Assertions.assertEquals(0, r.facultyClashes(),
                "regeneration must leave no faculty double-booking");
            org.junit.jupiter.api.Assertions.assertEquals(0, r.crossTimetableClashes(),
                "regeneration must not reuse any window of section A");
        }
    }
}
