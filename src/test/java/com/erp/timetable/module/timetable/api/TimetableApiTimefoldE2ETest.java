package com.erp.timetable.module.timetable.api;

import org.springframework.test.context.TestPropertySource;

/**
 * Phase 4 — API end-to-end verification of the real TT1 dataset through the
 * Timefold engine ({@code timetable.scheduler.engine=timefold}).
 *
 * <p>Verifies the full request path controller → service → engine → solver →
 * result mapper → persistence → response against the exported TT1 dataset, and
 * emits {@code E2E_METRIC} lines so the run can be compared with the Greedy run
 * ({@link TimetableApiGreedyE2ETest}) on the same data.
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=timefold",
    "spring.datasource.url=jdbc:h2:mem:tt1api_timefold;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class TimetableApiTimefoldE2ETest extends AbstractTimetableApiTt1E2E {

    @Override
    protected String engineName() {
        return "timefold";
    }

    @Override
    protected int expectedTt1Entries() {
        // The TT1 dataset now ships with a LAB classroom (CS-LAB1), so every
        // lesson has a legal placement: all 36 theory periods schedule into the
        // lecture hall and all 6 practical periods (CS471/CS785/CS691 × 2)
        // schedule into the LAB room → 42 entries, hard score 0, 0 unassigned.
        return TT1_TOTAL_DEMAND;
    }
}
