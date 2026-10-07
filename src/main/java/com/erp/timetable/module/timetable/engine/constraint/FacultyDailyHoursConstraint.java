package com.erp.timetable.module.timetable.engine.constraint;

import org.springframework.stereotype.Component;

@Component
public class FacultyDailyHoursConstraint implements SchedulingConstraint {

    // College-wide hard ceiling: no faculty may teach more than 5 periods in a single day,
    // regardless of what a faculty record's own maxDailyHours says. A faculty record's own
    // maxDailyHours remains the tighter binding limit when lower.
    private static final int COLLEGE_WIDE_MAX_DAILY_HOURS = 5;

    @Override
    public String getConstraintName() {
        return "FACULTY_DAILY_HOURS_LIMIT";
    }

    @Override
    public boolean isSatisfied(CandidatePlacement placement, ConstraintContext context) {
        if (placement.getFaculty() == null) return false;
        Integer facultyOwnCap = placement.getFaculty().getMaxDailyHours();
        int effectiveCap = (facultyOwnCap != null && facultyOwnCap > 0)
            ? Math.min(COLLEGE_WIDE_MAX_DAILY_HOURS, facultyOwnCap)
            : COLLEGE_WIDE_MAX_DAILY_HOURS;

        String fDayKey = placement.getFaculty().getId() + "_" + placement.getDayOfWeek();
        int currentDaily = context.getFacultyDailyHours().getOrDefault(fDayKey, 0);
        int proposedAddition = placement.getSlots().size();
        return (currentDaily + proposedAddition) <= effectiveCap;
    }

    @Override
    public String getViolationMessage(CandidatePlacement placement) {
        return "Faculty " + (placement.getFaculty() != null ? placement.getFaculty().getFullName() : "N/A") +
            " would exceed the maximum limit of 5 teaching hours per day on " + placement.getDayOfWeek();
    }
}
