package com.erp.timetable.module.timetable.planning.mapper;

import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.availability.repository.TimeSlotRepository;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import com.erp.timetable.module.timetable.planning.model.PlannableRoom;
import com.erp.timetable.module.timetable.planning.model.PlannableTimeSlot;
import com.erp.timetable.module.timetable.planning.model.PlanningLesson;
import com.erp.timetable.module.timetable.planning.model.SchedulingSolution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps the Timefold planning model back into the domain model.
 *
 * <p>Given a (possibly solver-modified) {@link SchedulingSolution}, updates the
 * matching {@link TimetableEntry} rows in the domain {@link Timetable} with the
 * assigned room and (day, time slot) window. Lessons created by the solver
 * without a source entry are appended as new entries.
 *
 * <p><b>Unassigned lessons.</b> Since Phase 5 the planning variables are
 * nullable, so a {@link PlanningLesson} may end up with a null room or window
 * (both null). An unassigned lesson has no placement to write:
 * <ul>
 *   <li>a lesson with a {@code sourceEntryId} maps to an existing row whose
 *       {@code day_of_week}/{@code time_slot_id}/{@code classroom_id} columns
 *       are NOT NULL — the row is left untouched (it is never deleted or
 *       cleared), so the unassigned state is preserved in the planning model
 *       and is observable, not silently dropped;</li>
 *   <li>a lesson without a source entry (fresh curriculum demand) that stays
 *       unassigned never creates a row, so no phantom entry is persisted.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SolverResultMapper {

    private final ClassroomRepository classroomRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final SubjectRepository subjectRepository;
    private final FacultyRepository facultyRepository;

    public void applyToTimetable(Timetable timetable, SchedulingSolution solution) {
        Map<Long, Classroom> classroomById = new HashMap<>();
        classroomRepository.findAll().forEach(c -> classroomById.put(c.getId(), c));

        Map<Long, TimeSlot> timeSlotById = new HashMap<>();
        timeSlotRepository.findAll().forEach(t -> timeSlotById.put(t.getId(), t));

        Map<Long, Subject> subjectById = new HashMap<>();
        subjectRepository.findAll().forEach(s -> subjectById.put(s.getId(), s));

        Map<Long, Faculty> facultyById = new HashMap<>();
        facultyRepository.findAll().forEach(f -> facultyById.put(f.getId(), f));

        Map<Long, TimetableEntry> entryById = new HashMap<>();
        timetable.getEntries().forEach(e -> entryById.put(e.getId(), e));

        for (PlanningLesson lesson : solution.getLessons()) {
            PlannableRoom assignedRoom = lesson.getRoom();
            PlannableTimeSlot assignedWindow = lesson.getTimeSlot();

            if (!isAssigned(lesson)) {
                // Unassigned lesson: preserve the unassigned state explicitly.
                // Existing rows are kept as-is (never deleted/cleared); fresh
                // demand that stayed unassigned creates no row.
                if (lesson.getSourceEntryId() != null) {
                    log.warn("Lesson {} (source entry {}) was left unassigned; keeping existing entry untouched",
                        lesson.getId(), lesson.getSourceEntryId());
                } else {
                    log.warn("Curriculum lesson {} ({}) was left unassigned; no entry row is created",
                        lesson.getId(), lesson.getSubject() != null ? lesson.getSubject().getSubjectCode() : "?");
                }
                continue;
            }

            Classroom classroom = classroomById.get(assignedRoom.getRoomId());
            TimeSlot timeSlot = timeSlotById.get(assignedWindow.getTimeSlotId());
            if (classroom == null || timeSlot == null) {
                continue;
            }

            if (lesson.getSourceEntryId() != null && entryById.containsKey(lesson.getSourceEntryId())) {
                TimetableEntry entry = entryById.get(lesson.getSourceEntryId());
                entry.setClassroom(classroom);
                entry.setDayOfWeek(assignedWindow.getDayOfWeek());
                entry.setTimeSlot(timeSlot);
            } else {
                // New lesson created by the solver → append a fresh entry.
                Subject subject = lesson.getSubject() != null
                    ? subjectById.get(lesson.getSubject().getSubjectId()) : null;
                Faculty faculty = lesson.getFaculty() != null
                    ? facultyById.get(lesson.getFaculty().getFacultyId()) : null;

                TimetableEntry entry = TimetableEntry.builder()
                    .timetable(timetable)
                    .classroom(classroom)
                    .dayOfWeek(assignedWindow.getDayOfWeek())
                    .timeSlot(timeSlot)
                    .subject(subject)
                    .faculty(faculty)
                    .section(timetable.getSection())
                    .isLab(lesson.isLab())
                    .isLocked(lesson.isLocked())
                    .build();
                timetable.addEntry(entry);
            }
        }
    }

    /** A lesson is assigned only when it has both a room and a (day, time slot) window. */
    private static boolean isAssigned(PlanningLesson lesson) {
        return lesson.getRoom() != null && lesson.getTimeSlot() != null;
    }
}
