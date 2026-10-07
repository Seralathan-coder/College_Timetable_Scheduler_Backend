package com.erp.timetable.module.timetable.engine.constraint;

import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.faculty.entity.Faculty;
import org.springframework.stereotype.Component;

@Component
public class FacultyAvailabilityConstraint implements SchedulingConstraint {

    @Override
    public String getConstraintName() {
        return "FACULTY_AVAILABILITY";
    }

    @Override
    public boolean isSatisfied(CandidatePlacement placement, ConstraintContext context) {
        Faculty faculty = placement.getFaculty();
        if (faculty == null) return false;

        // Skip faculty on LEAVE
        if ("LEAVE".equalsIgnoreCase(faculty.getStatus())) {
            return false;
        }

        String day = placement.getDayOfWeek();
        for (TimeSlot slot : placement.getSlots()) {
            String key = faculty.getId() + "_" + day + "_" + slot.getId();
            String status = context.getAvailabilityMap().get(key);
            if ("BLOCKED".equalsIgnoreCase(status) || "BUSY".equalsIgnoreCase(status)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String getViolationMessage(CandidatePlacement placement) {
        return "Faculty " + (placement.getFaculty() != null ? placement.getFaculty().getFullName() : "N/A") +
            " is marked on LEAVE or unavailable during the proposed time slot on " + placement.getDayOfWeek();
    }
}
