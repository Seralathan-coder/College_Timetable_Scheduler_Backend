package com.erp.timetable.module.timetable.benchmark;

import org.springframework.test.context.TestPropertySource;

/**
 * Phase 5 — Timefold engine benchmark through the real production scheduling path
 * ({@code TimetableService.generateTimetable}), solver engine, on the TT1
 * dataset. Phase-separated timing (solver / mapping / apply / persistence) is
 * parsed from the engine's log lines.
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=timefold",
    "spring.datasource.url=jdbc:h2:mem:bench_timefold;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class TimefoldEngineBenchmark extends AbstractEngineBenchmark {

    @Override
    protected String engineName() {
        return "timefold";
    }
}
