package com.erp.timetable.module.timetable.benchmark;

import org.springframework.test.context.TestPropertySource;

/**
 * TRANSIENT read-only verification probe — Greedy engine on the real TT1 dump.
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=greedy",
    "spring.datasource.url=jdbc:h2:mem:realver_greedy;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class RealDataVerificationGreedyProbe extends AbstractRealDataVerificationProbe {

    @Override
    protected String engineName() {
        return "greedy";
    }
}
