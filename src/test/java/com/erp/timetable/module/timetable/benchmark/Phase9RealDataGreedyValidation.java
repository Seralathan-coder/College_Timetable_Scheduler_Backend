package com.erp.timetable.module.timetable.benchmark;

import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 9 — real-data validation with the Greedy engine (default).
 *
 * <p>Deterministic rule-based expectations on the real dataset:
 * <ul>
 *   <li>Primary section (25): the engine reproduces the full demand — all 42
 *       periods placed (36 theory + 6 practical, the dump now ships with the
 *       LAB room CS-LAB1).</li>
 *   <li>Every section: requested == assigned + unassigned, no within-timetable
 *       clashes, and the independent cross-timetable DB recount stays at zero.</li>
 *   <li>Determinism: identical per-section assigned counts across all measured runs.</li>
 * </ul>
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=greedy",
    "spring.datasource.url=jdbc:h2:mem:p9_real_greedy;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class Phase9RealDataGreedyValidation extends AbstractPhase9RealDataValidation {

    @Override
    protected String engineName() {
        return "greedy";
    }

    @Override
    protected int expectedTt1Entries() {
        return 42; // all 36 theory + 6 practical periods placed (LAB room available)
    }

    @Override
    protected int expectedPrimarySectionAssigned() {
        return 42;
    }

    @Override
    protected void assertScenarioInvariants(List<List<SectionResult>> runs) {
        for (int i = 0; i < runs.get(0).size(); i++) {
            long sectionId = runs.get(0).get(i).sectionId();
            int firstAssigned = runs.get(0).get(i).assigned();
            for (int r = 1; r < runs.size(); r++) {
                assertEquals(firstAssigned, runs.get(r).get(i).assigned(),
                    "Greedy must be deterministic across runs (section " + sectionId + ")");
            }
        }

        SectionResult primary = runs.get(0).get(0);
        assertEquals(42, primary.assigned(), "Greedy primary section (25) must schedule all 42 periods");
        assertEquals(0, primary.conflicts(), "Greedy primary section (25) must finish conflict-free with the LAB room available");
        assertTrue(primary.solverInfeasible() == 0, "Greedy records no SOLVER_INFEASIBLE conflict");
    }
}
