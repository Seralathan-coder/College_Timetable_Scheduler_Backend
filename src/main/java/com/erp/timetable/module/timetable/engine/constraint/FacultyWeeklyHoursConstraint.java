package com.erp.timetable.module.timetable.engine.constraint;

import org.springframework.stereotype.Component;

/**
 * Ensures a faculty member's total periods across the whole week never exceed
 * their configured maxWeeklyHours (Faculty.maxWeeklyHours, default 24).
 * ConstraintContext.facultyWeeklyHours was already being accumulated by the
 * engine but, previously, nothing in the constraint pipeline ever read it -
 * this closes that gap so weekly faculty workload is genuinely respected.
 */
@Component
public class FacultyWeeklyHoursConstraint implements SchedulingConstraint {

    private static final int DEFAULT_MAX_WEEKLY_HOURS = 24;

    @Override
    public String getConstraintName() {
        return "FACULTY_WEEKLY_HOURS_LIMIT";
    }

    @Override
    public boolean isSatisfied(CandidatePlacement placement, ConstraintContext context) {
        if (placement.getFaculty() == null) return false;
        Integer facultyOwnCap = placement.getFaculty().getMaxWeeklyHours();
        int effectiveCap = (facultyOwnCap != null && facultyOwnCap > 0) ? facultyOwnCap : DEFAULT_MAX_WEEKLY_HOURS;

        Long facultyId = placement.getFaculty().getId();
        int currentWeekly = context.getFacultyWeeklyHours().getOrDefault(facultyId, 0);
        int proposedAddition = placement.getSlots().size();
        return (currentWeekly + proposedAddition) <= effectiveCap;
    }

    @Override
    public String getViolationMessage(CandidatePlacement placement) {
        return "Faculty " + (placement.getFaculty() != null ? placement.getFaculty().getFullName() : "N/A") +
            " would exceed their maximum weekly teaching hours";
    }
}
