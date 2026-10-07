package com.erp.timetable.module.timetable.engine.constraint;

import com.erp.timetable.module.classroom.entity.Classroom;
import org.springframework.stereotype.Component;

@Component
public class RoomTypeConstraint implements SchedulingConstraint {

    @Override
    public String getConstraintName() {
        return "ROOM_TYPE_MATCH";
    }

    @Override
    public boolean isSatisfied(CandidatePlacement placement, ConstraintContext context) {
        Classroom room = placement.getClassroom();

        if (placement.getSubject() == null || room == null) return false;

        // Room type follows the LESSON COMPONENT, not subject.subjectType: a
        // practical lesson (isLab=true) requires a LAB room and a theory lesson
        // requires a non-LAB room, even when the subject type is THEORY.
        boolean isLabRoom = "LAB".equalsIgnoreCase(room.getRoomType());

        return placement.isLab() == isLabRoom;
    }

    @Override
    public String getViolationMessage(CandidatePlacement placement) {
        return "Lesson " + (placement.getSubject() != null ? placement.getSubject().getSubjectCode() : "N/A") +
            " requires room type " + (placement.isLab() ? "LAB" : "LECTURE_HALL") +
            " but room " + (placement.getClassroom() != null ? placement.getClassroom().getRoomNumber() : "N/A") + " is " +
            (placement.getClassroom() != null ? placement.getClassroom().getRoomType() : "N/A");
    }
}
