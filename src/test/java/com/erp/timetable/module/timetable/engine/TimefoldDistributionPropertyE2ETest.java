package com.erp.timetable.module.timetable.engine;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "timetable.scheduler.engine=timefold")
class TimefoldDistributionPropertyE2ETest extends AbstractDistributionPropertyE2ETest {

    @Override
    protected String engineName() {
        return "timefold";
    }
}
