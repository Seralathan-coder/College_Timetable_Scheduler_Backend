package com.erp.timetable.module.timetable.benchmark;

import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 9 — real-data validation with the Timefold engine.
 *
 * <p>Constraint-solver expectations on the real dataset:
 * <ul>
 *   <li>Primary section (25): all 42 periods placed (36 theory + 6 practical,
 *       the dump now ships with the LAB room CS-LAB1), hard 0.</li>
 *   <li>Every section, including the resource-contended cross-timetable ones:
 *       hard score 0, no SOLVER_INFEASIBLE, requested == assigned + unassigned,
 *       and the independent cross-timetable DB recount stays at zero.</li>
 *   <li>Determinism: identical per-section assigned counts across all measured runs.</li>
 * </ul>
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=timefold",
    "spring.datasource.url=jdbc:h2:mem:p9_real_timefold;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class Phase9RealDataTimefoldValidation extends AbstractPhase9RealDataValidation {

    @Override
    protected String engineName() {
        return "timefold";
    }

    @Override
    protected int expectedTt1Entries() {
        return 42; // all 36 theory + 6 practical periods scheduled
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
                    "Timefold must be deterministic across runs (section " + sectionId + ")");
            }
        }

        for (List<SectionResult> run : runs) {
            for (SectionResult s : run) {
                assertEquals(0, s.hard(),
                    "Timefold must finish feasible (hard 0) for every section, saw hard " + s.hard()
                        + " on section " + s.sectionId());
                assertEquals(0, s.solverInfeasible(),
                    "no SOLVER_INFEASIBLE conflict on section " + s.sectionId());
                assertTrue(s.soft() <= 0, "soft score must be non-positive (section " + s.sectionId() + ")");
            }
        }

        SectionResult primary = runs.get(0).get(0);
        assertEquals(42, primary.assigned(), "Timefold primary section (25) must schedule all 42 periods");
        assertEquals(0, primary.unassigned(), "TT1 with a LAB room must leave nothing unassigned");
        assertEquals(0, primary.hard(), "the primary section must finish feasible (hard 0)");
        assertEquals(0, primary.conflicts(), "Timefold primary section (25) must finish conflict-free");
        assertTrue(primary.solverMs() > 0, "Timefold must emit a solver-phase time");
        assertTrue(primary.mappingMs() > 0, "Timefold must emit a mapping-phase time");
        assertTrue(primary.applyMs() > 0, "Timefold must emit an apply-phase time");
    }
}
