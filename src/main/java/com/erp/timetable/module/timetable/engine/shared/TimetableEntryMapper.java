package com.erp.timetable.module.timetable.engine.shared;

import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import org.springframework.stereotype.Component;

/**
 * Builds {@link TimetableEntry} instances for newly placed periods. Centralises
 * the entry construction so both the greedy engine and future engines produce
 * structurally identical entries.
 */
@Component
public class TimetableEntryMapper {

    public TimetableEntry buildEntry(String dayOfWeek, TimeSlot timeSlot, Subject subject,
            Faculty faculty, Classroom classroom, Section section, boolean isLab) {
        return TimetableEntry.builder()
            .dayOfWeek(dayOfWeek)
            .timeSlot(timeSlot)
            .subject(subject)
            .faculty(faculty)
            .classroom(classroom)
            .section(section)
            .isLab(isLab)
            .isLocked(false)
            .build();
    }
}
