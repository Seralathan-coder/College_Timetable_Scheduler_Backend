package com.erp.timetable.module.timetable.benchmark;

import org.springframework.test.context.TestPropertySource;

/**
 * TRANSIENT read-only verification probe — Timefold engine on the real TT1 dump.
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=timefold",
    "spring.datasource.url=jdbc:h2:mem:realver_timefold;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class RealDataVerificationTimefoldProbe extends AbstractRealDataVerificationProbe {

    @Override
    protected String engineName() {
        return "timefold";
    }
}
