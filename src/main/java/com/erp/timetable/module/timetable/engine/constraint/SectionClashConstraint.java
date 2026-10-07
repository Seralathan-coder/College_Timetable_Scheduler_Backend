package com.erp.timetable.module.timetable.engine.constraint;

import com.erp.timetable.module.availability.entity.TimeSlot;
import org.springframework.stereotype.Component;

/**
 * Ensures that a section/class timetable does not schedule two different subjects
 * at the same day and time slot.
 */
@Component
public class SectionClashConstraint implements SchedulingConstraint {

    @Override
    public String getConstraintName() {
        return "SECTION_CLASH";
    }

    @Override
    public boolean isSatisfied(CandidatePlacement placement, ConstraintContext context) {
        if (placement.getSlots() == null || context.getSectionOccupancy() == null) {
            return true;
        }

        // Section-aware key: two different sections may share the same (day, slot)
        // but a single section may never have two classes in the same (day, slot).
        Long sectionId = placement.getSectionId();
        if (sectionId == null) {
            return true; // No section context — nothing to enforce
        }

        String day = placement.getDayOfWeek();
        for (TimeSlot slot : placement.getSlots()) {
            String slotKey = sectionId + "_" + day + "_" + slot.getId();
            if (Boolean.TRUE.equals(context.getSectionOccupancy().get(slotKey))) {
                return false; // Section already has a class scheduled at this slot
            }
        }
        return true;
    }

    @Override
    public String getViolationMessage(CandidatePlacement placement) {
        return "The section is already scheduled for another subject at the same time on " + placement.getDayOfWeek();
    }
}
