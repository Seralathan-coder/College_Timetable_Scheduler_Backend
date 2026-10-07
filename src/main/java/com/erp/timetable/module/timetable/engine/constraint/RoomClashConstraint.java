package com.erp.timetable.module.timetable.engine.constraint;

import com.erp.timetable.module.availability.entity.TimeSlot;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RoomClashConstraint implements SchedulingConstraint {

    @Override
    public String getConstraintName() {
        return "ROOM_CLASH";
    }

    @Override
    public boolean isSatisfied(CandidatePlacement placement, ConstraintContext context) {
        if (placement.getClassroom() == null) return false;
        Long roomId = placement.getClassroom().getId();
        String day = placement.getDayOfWeek();

        for (TimeSlot slot : placement.getSlots()) {
            String slotKey = day + "_" + slot.getId();
            // In-memory occupancy check
            Set<Long> occupied = context.getRoomOccupancy().get(slotKey);
            if (occupied != null && occupied.contains(roomId)) {
                return false;
            }
            // Database clash check across all timetables
            if (context.getEntryRepository() != null &&
                !context.getEntryRepository().findRoomClashes(roomId, day, slot.getId()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String getViolationMessage(CandidatePlacement placement) {
        return "Classroom " + (placement.getClassroom() != null ? placement.getClassroom().getRoomNumber() : "N/A") +
            " is already occupied by another class at the same time on " + placement.getDayOfWeek();
    }
}
