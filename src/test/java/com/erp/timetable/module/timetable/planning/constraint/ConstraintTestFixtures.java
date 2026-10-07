package com.erp.timetable.module.timetable.planning.constraint;

import com.erp.timetable.module.timetable.planning.model.AvailabilityFact;
import com.erp.timetable.module.timetable.planning.model.OccupancyFact;
import com.erp.timetable.module.timetable.planning.model.PlannableFaculty;
import com.erp.timetable.module.timetable.planning.model.PlannableRoom;
import com.erp.timetable.module.timetable.planning.model.PlannableSubject;
import com.erp.timetable.module.timetable.planning.model.PlannableTimeSlot;
import com.erp.timetable.module.timetable.planning.model.PlanningLesson;

import java.time.LocalTime;

/**
 * Shared fixtures for the Phase 3B constraint tests.
 *
 * <p>Note on grouping: the daily/weekly-hours and consecutive-teaching
 * constraints group lessons by the {@link PlannableFaculty} <em>instance</em>
 * (as produced by {@code TimetablePlanningMapper}, one instance per faculty).
 * Tests that exercise those constraints must therefore reuse a single faculty
 * instance across all lessons of the group.
 */
public final class ConstraintTestFixtures {

    private ConstraintTestFixtures() {
    }

    public static PlannableFaculty faculty(long id) {
        return PlannableFaculty.builder()
            .facultyId(id)
            .employeeId("EMP-" + id)
            .departmentId(id)
            .departmentName("Dept " + id)
            .status("AVAILABLE")
            .build();
    }

    public static PlannableFaculty faculty(long id, Integer maxDailyHours, Integer maxWeeklyHours) {
        return PlannableFaculty.builder()
            .facultyId(id)
            .employeeId("EMP-" + id)
            .departmentId(id)
            .departmentName("Dept " + id)
            .maxDailyHours(maxDailyHours)
            .maxWeeklyHours(maxWeeklyHours)
            .status("AVAILABLE")
            .build();
    }

    public static PlannableRoom room(long id) {
        return PlannableRoom.builder()
            .roomId(id)
            .roomNumber("R-" + id)
            .roomType("LECTURE_HALL")
            .capacity(60)
            .build();
    }

    public static PlannableRoom room(long id, String roomType, Integer capacity) {
        return PlannableRoom.builder()
            .roomId(id)
            .roomNumber("R-" + id)
            .roomType(roomType)
            .capacity(capacity)
            .build();
    }

    public static PlannableTimeSlot window(long slotId, String dayOfWeek) {
        return PlannableTimeSlot.builder()
            .timeSlotId(slotId)
            .dayOfWeek(dayOfWeek)
            .slotOrder(1)
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(10, 0))
            .isBreak(false)
            .build();
    }

    public static PlannableTimeSlot window(long slotId, String dayOfWeek, int slotOrder) {
        return PlannableTimeSlot.builder()
            .timeSlotId(slotId)
            .dayOfWeek(dayOfWeek)
            .slotOrder(slotOrder)
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(10, 0))
            .isBreak(false)
            .build();
    }

    public static PlannableSubject subject(long id, String code, String subjectType) {
        return PlannableSubject.builder()
            .subjectId(id)
            .subjectCode(code)
            .subjectName("Subject " + code)
            .subjectType(subjectType)
            .build();
    }

    public static PlannableSubject subject(long id, String code, String subjectType, Long assignedFacultyId) {
        return PlannableSubject.builder()
            .subjectId(id)
            .subjectCode(code)
            .subjectName("Subject " + code)
            .subjectType(subjectType)
            .assignedFacultyId(assignedFacultyId)
            .build();
    }

    public static PlannableSubject subject(long id, String code, String subjectType, Long assignedFacultyId,
            Integer weeklyHours) {
        return subject(id, code, subjectType, assignedFacultyId, weeklyHours, null);
    }

    /**
     * Subject fixture that also carries the stored consecutive-period block size —
     * the value that decides whether a 2-hour subject plans as ONE 2-period block
     * or as two single periods.
     */
    public static PlannableSubject subject(long id, String code, String subjectType, Long assignedFacultyId,
            Integer weeklyHours, Integer sessionBlockSize) {
        return PlannableSubject.builder()
            .subjectId(id)
            .subjectCode(code)
            .subjectName("Subject " + code)
            .subjectType(subjectType)
            .assignedFacultyId(assignedFacultyId)
            .weeklyHours(weeklyHours)
            .sessionBlockSize(sessionBlockSize)
            .build();
    }

    public static AvailabilityFact availabilityFact(long facultyId, String dayOfWeek, long slotId, String slotType) {
        return AvailabilityFact.builder()
            .facultyId(facultyId)
            .dayOfWeek(dayOfWeek)
            .timeSlotId(slotId)
            .slotType(slotType)
            .build();
    }

    public static OccupancyFact occupancyFact(Long facultyId, Long roomId, String dayOfWeek, long slotId) {
        return OccupancyFact.builder()
            .facultyId(facultyId)
            .roomId(roomId)
            .dayOfWeek(dayOfWeek)
            .timeSlotId(slotId)
            .build();
    }

    public static PlanningLesson lesson(long id, long sectionId, PlannableFaculty faculty,
            PlannableSubject subject, PlannableRoom room, PlannableTimeSlot timeSlot) {
        return lesson(id, sectionId, faculty, subject,
            faculty != null ? faculty.getDepartmentId() : null, 40, room, timeSlot);
    }

    public static PlanningLesson lesson(long id, long sectionId, PlannableFaculty faculty,
            PlannableSubject subject, Long departmentId, Integer requiredCapacity,
            PlannableRoom room, PlannableTimeSlot timeSlot) {
        return PlanningLesson.builder()
            .id(id)
            .sectionId(sectionId)
            .faculty(faculty)
            .subject(subject)
            .departmentId(departmentId)
            .requiredCapacity(requiredCapacity != null ? requiredCapacity : 40)
            .isLab(subject != null && "LAB".equalsIgnoreCase(subject.getSubjectType()))
            .room(room)
            .timeSlot(timeSlot)
            .build();
    }
}
