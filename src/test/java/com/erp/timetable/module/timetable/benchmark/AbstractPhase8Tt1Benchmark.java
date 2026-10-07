package com.erp.timetable.module.timetable.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.system.CapturedOutput;

import java.time.Duration;

/**
 * Phase 8 — Scenario A: single-section generation on the real TT1 dataset
 * through the production REST path. TT1 now ships with a LAB room (CS-LAB1)
 * added to the dump, so the 6 practical periods (CS471/CS785/CS691 × 2) are
 * schedulable: all 42 periods (36 theory + 6 practical) are assigned.
 */
public abstract class AbstractPhase8Tt1Benchmark extends AbstractPhase8Benchmark {

    private static final String SESSION = "2025-2026 EVEN";

    @Override
    protected String scenarioName() {
        return "A";
    }

    @Override
    protected RunResult runScenarioOnce(CapturedOutput output) throws Exception {
        String before = output.getAll();
        long start = System.nanoTime();
        JsonNode data = postGenerateExpectSuccess(TT1_DEPARTMENT_ID, TT1_SECTION_ID, TT1_SEMESTER, SESSION);
        long apiMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        String logs = logsSince(output, before);

        int requested = TT1_TOTAL_DEMAND;
        int assigned = data.get("entries").size();
        int unassigned = requested - assigned;

        int[] hs = parseHardSoft(logs);
        int hard = hs != null ? hs[0] : N_A;
        int soft = hs != null ? hs[1] : N_A;

        int conflicts = data.get("conflictCount").asInt();
        int solverInfeasible = countSolverInfeasible(data);

        ClashCounts clashes = countClashes(data.get("entries"));

        long[] timings = parseEngineTimings(logs);
        long engineMs = timings[0];
        long persistMs = engineMs > 0 ? Math.max(0L, apiMs - engineMs) : N_A;

        return new RunResult(requested, assigned, unassigned, hard, soft, conflicts, solverInfeasible,
            clashes.faculty(), clashes.room(), clashes.section(),
            0, apiMs, N_A, timings[1], timings[2], timings[3], persistMs,
            countOtherTimetableEntries(data.get("id").asLong()),
            data.get("optimizationScore").asInt());
    }
}
