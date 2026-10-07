package com.erp.timetable.module.timetable.engine.constraint;

public interface SchedulingConstraint {
    String getConstraintName();
    boolean isSatisfied(CandidatePlacement placement, ConstraintContext context);
    String getViolationMessage(CandidatePlacement placement);
}
