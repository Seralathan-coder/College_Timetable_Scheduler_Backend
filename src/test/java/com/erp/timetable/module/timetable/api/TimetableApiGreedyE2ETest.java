package com.erp.timetable.module.timetable.api;

import org.springframework.test.context.TestPropertySource;

/**
 * Phase 4 — API end-to-end verification of the real TT1 dataset through the
 * Greedy engine ({@code timetable.scheduler.engine=greedy}, the default).
 *
 * <p>Baseline for the Greedy-vs-Timefold comparison: identical assertions and
 * metrics as {@link TimetableApiTimefoldE2ETest}, on the same TT1 dataset. The
 * dump was extended with a LAB classroom (CS-LAB1) so the dataset is
 * schedulable end-to-end: all 36 theory periods in the lecture hall and all 6
 * practical periods in the lab.
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=greedy",
    "spring.datasource.url=jdbc:h2:mem:tt1api_greedy;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class TimetableApiGreedyE2ETest extends AbstractTimetableApiTt1E2E {

    @Override
    protected String engineName() {
        return "greedy";
    }

    @Override
    protected int expectedTt1Entries() {
        return TT1_TOTAL_DEMAND; // all 36 theory periods placed in the lecture hall, all 6 practical periods in the LAB room
    }

    @Override
    protected int expectedTt1RegenerateEntries() {
        // Partial regeneration restores the full demand (42) around the locked
        // entries — the mop-up pass repairs any greedy dead-end.
        return TT1_TOTAL_DEMAND;
    }
}
