package com.erp.timetable.module.timetable.engine.constraint;

import org.springframework.stereotype.Component;

/**
 * College policy now ALLOWS faculty to teach back-to-back periods without any
 * maximum-consecutive or minimum-free-gap restriction (the old rule capped
 * theory at 2 consecutive periods and demanded 2 free periods after a block).
 *
 * <p>This constraint is kept registered so the hard-constraint pipeline and
 * conflict reporting still recognise the name, but it never rejects a
 * placement. The faculty daily-hour cap and per-faculty maxDailyHours remain
 * the binding limits on how much a faculty member can teach in one day.
 */
@Component
public class ConsecutiveTeachingConstraint implements SchedulingConstraint {

    @Override
    public String getConstraintName() {
        return "CONSECUTIVE_TEACHING_RULE";
    }

    @Override
    public boolean isSatisfied(CandidatePlacement placement, ConstraintContext context) {
        return true;
    }

    @Override
    public String getViolationMessage(CandidatePlacement placement) {
        return "";
    }
}
