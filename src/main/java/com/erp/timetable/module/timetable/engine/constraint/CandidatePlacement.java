package com.erp.timetable.module.timetable.engine.constraint;

import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.subject.entity.Subject;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class CandidatePlacement {
    private final Subject subject;
    private final Faculty faculty;
    private final Classroom classroom;
    private final String dayOfWeek;
    private final List<TimeSlot> slots;
    private final Long departmentId;
    private final Long sectionId;
    /**
     * The lesson component being placed: {@code true} for a practical lesson
     * (requires a LAB room), {@code false} for a theory lesson (requires a
     * non-LAB room). Decoupled from {@code subject.subjectType}: CS142 and CS795
     * are THEORY subjects that carry 2 practical hours each.
     */
    private final boolean isLab;
}
