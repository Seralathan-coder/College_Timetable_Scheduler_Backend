package com.erp.timetable.module.timetable.planning.mapper;

import com.erp.timetable.module.availability.entity.FacultyAvailability;
import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import com.erp.timetable.module.timetable.planning.model.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Maps the domain model into the Timefold planning model.
 *
 * <p>A domain {@link Timetable} (with its entries, subjects, faculty, classrooms,
 * time slots and availability facts) becomes a {@link SchedulingSolution}. Each
 * existing {@link TimetableEntry} becomes one {@link PlanningLesson} whose room
 * and time-slot windows are initialised to the entry's current placement so the
 * solution is a faithful snapshot of the dataset.
 *
 * <p>Phase 4: the {@link #toSolution(Timetable, List, List, List, List, List)}
 * overload accepts an explicit lesson list (locked-preserved lessons plus the
 * curriculum demand built by the {@code timefold} engine), and the {@code to*}
 * conversion helpers are public so the engine can construct lessons that share
 * the exact {@link PlannableSubject}/{@link PlannableFaculty}/{@link PlannableRoom}/
 * {@link PlannableTimeSlot} instances used as problem facts.
 */
@Component
public class TimetablePlanningMapper {

    /**
     * Converts a timetable into a planning solution.
     *
     * @param rooms       the classrooms available to the solver (value range)
     * @param timeSlots   the raw time slots available (breaks filtered out here)
     * @param availability faculty availability facts
     * @param workingDays valid day-of-week labels (e.g. MON..SAT)
     */
    public SchedulingSolution toSolution(Timetable timetable,
            List<Classroom> rooms, List<TimeSlot> timeSlots,
            List<FacultyAvailability> availability, List<String> workingDays) {

        // ── Subjects & faculty referenced by the timetable ──
        Map<Long, PlannableSubject> subjectMap = new LinkedHashMap<>();
        Map<Long, PlannableFaculty> facultyMap = new LinkedHashMap<>();
        for (TimetableEntry entry : timetable.getEntries()) {
            Subject s = entry.getSubject();
            if (s != null && !subjectMap.containsKey(s.getId())) {
                subjectMap.put(s.getId(), toPlannableSubject(s));
            }
            Faculty f = entry.getFaculty();
            if (f != null && !facultyMap.containsKey(f.getId())) {
                facultyMap.put(f.getId(), toPlannableFaculty(f));
            }
        }

        // ── Value ranges ──
        List<PlannableRoom> plannableRooms = toPlannableRooms(rooms);
        List<PlannableTimeSlot> plannableTimeSlots = toPlannableTimeSlots(timeSlots, workingDays);

        // ── Lessons from existing entries ──
        Map<String, PlannableRoom> roomByKey = new HashMap<>();
        for (PlannableRoom r : plannableRooms) {
            roomByKey.put(r.getRoomId() + "", r);
        }
        Map<String, PlannableTimeSlot> slotByKey = new HashMap<>();
        for (PlannableTimeSlot t : plannableTimeSlots) {
            slotByKey.put(t.getTimeSlotId() + "|" + t.getDayOfWeek(), t);
        }

        List<PlanningLesson> lessons = new ArrayList<>();
        Map<Long, long[]> labSessions = labSessionMetadataByEntryId(timetable.getEntries());
        for (TimetableEntry entry : timetable.getEntries()) {
            PlannableRoom room = entry.getClassroom() != null
                ? roomByKey.get(entry.getClassroom().getId() + "")
                : null;
            PlannableTimeSlot window = entry.getTimeSlot() != null
                ? slotByKey.get(entry.getTimeSlot().getId() + "|" + entry.getDayOfWeek())
                : null;

            long[] session = labSessions.get(entry.getId());
            lessons.add(PlanningLesson.builder()
                .id(entry.getId())
                .sourceEntryId(entry.getId())
                .subject(subjectMap.get(entry.getSubject() != null ? entry.getSubject().getId() : null))
                .faculty(facultyMap.get(entry.getFaculty() != null ? entry.getFaculty().getId() : null))
                .sectionId(entry.getSection() != null ? entry.getSection().getId() : null)
                .departmentId(timetable.getDepartment() != null ? timetable.getDepartment().getId() : null)
                .academicYearId(entry.getSection() != null && entry.getSection().getAcademicYear() != null
                    ? entry.getSection().getAcademicYear().getId()
                    : null)
                .requiredCapacity(entry.getSection() != null && entry.getSection().getStudentStrength() != null
                    ? entry.getSection().getStudentStrength()
                    : 40)
                .isLab(Boolean.TRUE.equals(entry.getIsLab()))
                .locked(Boolean.TRUE.equals(entry.getIsLocked()))
                .practicalSessionId(session != null ? session[0] : null)
                .practicalSessionSize(session != null ? (int) session[1] : null)
                .room(room)
                .timeSlot(window)
                .build());
        }

        return toSolutionFromLessons(timetable, availability, lessons, plannableRooms, plannableTimeSlots);
    }

    /**
     * Derives practical-session metadata for persisted lab entries: each
     * consecutive run of lab entries sharing (subject, faculty, section, day)
     * becomes one session whose size equals the run length. This reconstructs
     * the current state rather than imposing the configured block size, so a
     * mapped timetable is always internally consistent. Returns
     * {@code entryId → {sessionId, sessionSize}} for lab entries with a placed
     * window; entries with no window are omitted.
     */
    public static Map<Long, long[]> labSessionMetadataByEntryId(List<TimetableEntry> entries) {
        Map<Long, long[]> info = new HashMap<>();
        Map<String, List<TimetableEntry>> byKey = new LinkedHashMap<>();
        for (TimetableEntry e : entries) {
            if (!Boolean.TRUE.equals(e.getIsLab()) || e.getTimeSlot() == null) continue;
            String key = (e.getSubject() != null ? e.getSubject().getId() : -1L) + "|"
                + (e.getFaculty() != null ? e.getFaculty().getId() : -1L) + "|"
                + (e.getSection() != null ? e.getSection().getId() : -1L) + "|" + e.getDayOfWeek();
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        long sessionSeq = 1L;
        for (List<TimetableEntry> group : byKey.values()) {
            group.sort(Comparator.comparingInt(e -> e.getTimeSlot().getSlotOrder()));
            int runStart = 0;
            for (int i = 1; i <= group.size(); i++) {
                if (i == group.size()
                    || group.get(i).getTimeSlot().getSlotOrder() != group.get(i - 1).getTimeSlot().getSlotOrder() + 1) {
                    int runLength = i - runStart;
                    for (int j = runStart; j < i; j++) {
                        info.put(group.get(j).getId(), new long[] { sessionSeq, runLength });
                    }
                    sessionSeq++;
                    runStart = i;
                }
            }
        }
        return info;
    }

    /**
     * Converts the other timetables' entries into cross-timetable occupancy
     * facts for the current solve (Phase 7). Entries belonging to the timetable
     * being generated are skipped — their placements are managed by the
     * locked-entry preservation logic and must never be treated as foreign
     * occupancy. One fact per foreign entry carries both the faculty and room
     * ids, mirroring the Greedy engine's whole-college occupancy context
     * ({@code TimetableGeneratorEngine#buildContext}).
     *
     * @param existingEntries    every timetable entry loaded for the relevant
     *                           faculty (the {@code timefold} engine loads via
     *                           {@code findByFacultyIdIn}, like Greedy)
     * @param currentTimetableId the id of the timetable being generated, whose
     *                           own entries are excluded from the facts
     */
    public List<OccupancyFact> toOccupancyFacts(List<TimetableEntry> existingEntries, Long currentTimetableId) {
        List<OccupancyFact> facts = new ArrayList<>();
        for (TimetableEntry entry : existingEntries) {
            if (currentTimetableId != null && entry.getTimetable() != null
                    && Objects.equals(entry.getTimetable().getId(), currentTimetableId)) {
                continue;
            }
            if (entry.getFaculty() == null || entry.getTimeSlot() == null) {
                continue;
            }
            facts.add(OccupancyFact.builder()
                .facultyId(entry.getFaculty().getId())
                .roomId(entry.getClassroom() != null ? entry.getClassroom().getId() : null)
                .dayOfWeek(entry.getDayOfWeek())
                .timeSlotId(entry.getTimeSlot().getId())
                .build());
        }
        return facts;
    }

    /**
     * Assembles a solution from an explicit lesson list and pre-built value
     * ranges. The problem facts (subjects, faculty, availability) are derived
     * from the lessons themselves; the room/time-slot window instances are the
     * exact instances used to preset pinned lessons, so they are guaranteed to
     * be members of the value ranges.
     *
     * <p>Used by the {@code timefold} scheduling engine, which constructs the
     * lessons itself (locked-preserved placements plus curriculum demand) and
     * must therefore share the problem-fact and value-range instances across
     * lessons and the solution.
     */
    public SchedulingSolution toSolutionFromLessons(Timetable timetable,
            List<FacultyAvailability> availability,
            List<PlanningLesson> lessons,
            List<PlannableRoom> rooms,
            List<PlannableTimeSlot> timeSlots) {
        return toSolutionFromLessons(timetable, availability, lessons, rooms, timeSlots, Collections.emptyList());
    }

    /**
     * Assembles a solution from an explicit lesson list and pre-built value
     * ranges. The problem facts (subjects, faculty, availability, occupancy) are
     * derived from the lessons themselves (occupancy from the caller, see
     * {@link #toOccupancyFacts(List, Long)}); the room/time-slot window
     * instances are the exact instances used to preset pinned lessons, so they
     * are guaranteed to be members of the value ranges.
     *
     * <p>Used by the {@code timefold} scheduling engine, which constructs the
     * lessons itself (locked-preserved placements plus curriculum demand) and
     * must therefore share the problem-fact and value-range instances across
     * lessons and the solution. The {@code occupancyFacts} list carries the
     * cross-timetable clash claims of every other timetable (Phase 7).
     */
    public SchedulingSolution toSolutionFromLessons(Timetable timetable,
            List<FacultyAvailability> availability,
            List<PlanningLesson> lessons,
            List<PlannableRoom> rooms,
            List<PlannableTimeSlot> timeSlots,
            List<OccupancyFact> occupancyFacts) {

        Set<PlannableSubject> subjects = new LinkedHashSet<>();
        Set<PlannableFaculty> faculty = new LinkedHashSet<>();
        for (PlanningLesson lesson : lessons) {
            if (lesson.getSubject() != null) {
                subjects.add(lesson.getSubject());
            }
            if (lesson.getFaculty() != null) {
                faculty.add(lesson.getFaculty());
            }
        }

        return SchedulingSolution.builder()
            .timetableId(timetable.getId())
            .academicSession(timetable.getAcademicSession())
            .departmentId(timetable.getDepartment() != null ? timetable.getDepartment().getId() : null)
            .sectionId(timetable.getSection() != null ? timetable.getSection().getId() : null)
            .semester(timetable.getSemester())
            .lessons(lessons)
            .subjects(new ArrayList<>(subjects))
            .faculty(new ArrayList<>(faculty))
            .availabilityFacts(availability.stream().map(this::toAvailabilityFact).toList())
            .occupancyFacts(occupancyFacts != null ? occupancyFacts : Collections.emptyList())
            .rooms(rooms)
            .timeSlots(timeSlots)
            .build();
    }

    public PlannableSubject toPlannableSubject(Subject s) {
        return PlannableSubject.builder()
            .subjectId(s.getId())
            .subjectCode(s.getSubjectCode())
            .subjectName(s.getSubjectName())
            .subjectType(s.getSubjectType())
            .semester(s.getSemester())
            .departmentId(s.getDepartment() != null ? s.getDepartment().getId() : null)
            .sectionId(s.getSection() != null ? s.getSection().getId() : null)
            .assignedFacultyId(s.getAssignedFaculty() != null ? s.getAssignedFaculty().getId() : null)
            .theoryHours(s.getTheoryHours())
            .practicalHours(s.getPracticalHours())
            .sessionBlockSize(s.getSessionBlockSize())
            .weeklyHours(s.getCalculatedWeeklyHours())
            .active(Boolean.TRUE.equals(s.getIsActive()))
            .build();
    }

    public PlannableFaculty toPlannableFaculty(Faculty f) {
        return PlannableFaculty.builder()
            .facultyId(f.getId())
            .employeeId(f.getEmployeeId())
            .fullName(f.getFullName())
            .departmentId(f.getDepartment() != null ? f.getDepartment().getId() : null)
            .departmentName(f.getDepartment() != null ? f.getDepartment().getName() : null)
            .teachingDepartments(f.getTeachingDepartments())
            .specialization(f.getSpecialization())
            .assignedSubjectCodes(f.getAssignedSubjectCodes())
            .maxDailyHours(f.getMaxDailyHours())
            .maxWeeklyHours(f.getMaxWeeklyHours())
            .status(f.getStatus())
            .build();
    }

    public List<PlannableRoom> toPlannableRooms(List<Classroom> rooms) {
        return rooms.stream().map(this::toPlannableRoom).toList();
    }

    public List<PlannableTimeSlot> toPlannableTimeSlots(List<TimeSlot> timeSlots,
            List<String> workingDays) {
        List<PlannableTimeSlot> windows = new ArrayList<>();
        for (String day : workingDays) {
            for (TimeSlot ts : timeSlots) {
                if (Boolean.TRUE.equals(ts.getIsBreak())) {
                    continue;
                }
                windows.add(PlannableTimeSlot.builder()
                    .timeSlotId(ts.getId())
                    .dayOfWeek(day)
                    .slotOrder(ts.getSlotOrder())
                    .startTime(ts.getStartTime())
                    .endTime(ts.getEndTime())
                    .isBreak(false)
                    .slotLabel(ts.getSlotLabel())
                    .build());
            }
        }
        return windows;
    }

    private PlannableRoom toPlannableRoom(Classroom c) {
        return PlannableRoom.builder()
            .roomId(c.getId())
            .roomNumber(c.getRoomNumber())
            .roomName(c.getRoomName())
            .building(c.getBuilding())
            .roomType(c.getRoomType())
            .capacity(c.getCapacity())
            .departmentId(c.getDepartment() != null ? c.getDepartment().getId() : null)
            .academicYearId(c.getAcademicYear() != null ? c.getAcademicYear().getId() : null)
            .sectionId(c.getSection() != null ? c.getSection().getId() : null)
            .build();
    }

    private AvailabilityFact toAvailabilityFact(FacultyAvailability a) {
        return AvailabilityFact.builder()
            .facultyId(a.getFaculty() != null ? a.getFaculty().getId() : null)
            .dayOfWeek(a.getDayOfWeek())
            .timeSlotId(a.getTimeSlot() != null ? a.getTimeSlot().getId() : null)
            .slotType(a.getSlotType())
            .build();
    }
}
