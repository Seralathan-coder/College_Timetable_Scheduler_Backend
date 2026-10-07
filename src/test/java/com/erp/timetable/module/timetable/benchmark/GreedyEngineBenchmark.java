package com.erp.timetable.module.timetable.benchmark;

import org.springframework.test.context.TestPropertySource;

/**
 * Phase 5 — Greedy engine benchmark through the real production scheduling path
 * ({@code TimetableService.generateTimetable}), default engine, on the TT1
 * dataset. Greedy has no separable solver phase, so solver/mapping/apply/persist
 * breakdowns are reported as not applicable.
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=greedy",
    "spring.datasource.url=jdbc:h2:mem:bench_greedy;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class GreedyEngineBenchmark extends AbstractEngineBenchmark {

    @Override
    protected String engineName() {
        return "greedy";
    }
}
