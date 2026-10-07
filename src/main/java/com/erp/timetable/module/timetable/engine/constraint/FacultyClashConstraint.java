package com.erp.timetable.module.timetable.engine.constraint;

import com.erp.timetable.module.availability.entity.TimeSlot;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class FacultyClashConstraint implements SchedulingConstraint {

    @Override
    public String getConstraintName() {
        return "FACULTY_CLASH";
    }

    @Override
    public boolean isSatisfied(CandidatePlacement placement, ConstraintContext context) {
        if (placement.getFaculty() == null) return false;
        Long facultyId = placement.getFaculty().getId();
        String day = placement.getDayOfWeek();

        for (TimeSlot slot : placement.getSlots()) {
            String slotKey = day + "_" + slot.getId();
            // In-memory occupancy check
            Set<Long> occupied = context.getFacultyOccupancy().get(slotKey);
            if (occupied != null && occupied.contains(facultyId)) {
                return false;
            }
            // Database clash check across all timetables
            if (context.getEntryRepository() != null &&
                !context.getEntryRepository().findFacultyClashes(facultyId, day, slot.getId()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String getViolationMessage(CandidatePlacement placement) {
        return "Faculty " + (placement.getFaculty() != null ? placement.getFaculty().getFullName() : "N/A") +
            " is already scheduled in another class at the same time on " + placement.getDayOfWeek();
    }
}
