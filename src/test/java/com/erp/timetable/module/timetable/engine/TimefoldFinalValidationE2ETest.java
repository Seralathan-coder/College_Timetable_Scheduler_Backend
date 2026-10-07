package com.erp.timetable.module.timetable.engine;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "timetable.scheduler.engine=timefold")
class TimefoldFinalValidationE2ETest extends AbstractFinalValidationE2ETest {

    @Override
    protected String engineName() {
        return "timefold";
    }

    @Test
    @Override
    void subjectCreator_dynamicDemand_reactsToDatabaseChanges() {
        super.subjectCreator_dynamicDemand_reactsToDatabaseChanges();
    }

    @Test
    @Override
    void infeasibleLabCapacity_reportsStructuredDiagnostic() {
        super.infeasibleLabCapacity_reportsStructuredDiagnostic();
    }

    @Test
    @Override
    void facultyAvailability_failureNamesAvailabilityNotCapacity() {
        super.facultyAvailability_failureNamesAvailabilityNotCapacity();
    }

    @Test
    @Override
    void blockSizes_1_2_3_produceExactSessionsAndEnginesAgree() {
        super.blockSizes_1_2_3_produceExactSessionsAndEnginesAgree();
    }

    @Test
    @Override
    void partialRegeneration_preservesLocked_backfillsExactRemainder() {
        super.partialRegeneration_preservesLocked_backfillsExactRemainder();
    }
}
