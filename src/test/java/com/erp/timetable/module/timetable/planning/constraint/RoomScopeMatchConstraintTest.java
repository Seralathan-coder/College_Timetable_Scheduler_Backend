package com.erp.timetable.module.timetable.planning.constraint;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import com.erp.timetable.module.timetable.planning.model.PlannableRoom;
import com.erp.timetable.module.timetable.planning.model.PlannableSubject;
import com.erp.timetable.module.timetable.planning.model.PlanningLesson;
import com.erp.timetable.module.timetable.planning.model.SchedulingSolution;
import org.junit.jupiter.api.Test;

import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.faculty;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.room;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.subject;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.window;

/**
 * Tests for {@code TimetableConstraintProvider#roomScopeMatch} — a classroom
 * owned by an academic year / section may only be assigned to a lesson of the
 * matching scope, while NULL ownership (shared/global rooms) skips every check.
 * The classroom's department is organizational metadata, not a scheduling
 * filter.
 */
class RoomScopeMatchConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableSubject SUBJECT = subject(1, "PHY101", "THEORY", 1L);

    private static PlannableRoom scopedRoom(long id, Long deptId, Long yearId, Long sectionId) {
        return PlannableRoom.builder()
            .roomId(id)
            .roomNumber("R-" + id)
            .roomType("LECTURE_HALL")
            .capacity(60)
            .departmentId(deptId)
            .academicYearId(yearId)
            .sectionId(sectionId)
            .build();
    }

    private static PlanningLesson scopedLesson(long id, Long deptId, Long yearId, Long sectionId,
            PlannableRoom room) {
        return PlanningLesson.builder()
            .id(id)
            .sectionId(sectionId)
            .faculty(faculty(1))
            .subject(SUBJECT)
            .departmentId(deptId)
            .academicYearId(yearId)
            .requiredCapacity(40)
            .room(room)
            .timeSlot(window(id, "MON", 1))
            .build();
    }

    @Test
    void sharedRoom_withNoScopeFields_isNeverPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomScopeMatch)
            .given(scopedLesson(1, 1L, 10L, 100L, room(1)))
            .hasNoImpact();
    }

    @Test
    void roomScopedToSameDepartment_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomScopeMatch)
            .given(scopedLesson(1, 1L, null, null, scopedRoom(1, 1L, null, null)))
            .hasNoImpact();
    }

    @Test
    void roomScopedToDifferentDepartmentButNoYearOrSection_isStillShared() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomScopeMatch)
            .given(scopedLesson(1, 1L, null, null, scopedRoom(1, 2L, null, null)))
            .hasNoImpact();
    }

    @Test
    void roomScopedToDifferentDepartmentButMatchingYearAndSection_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomScopeMatch)
            .given(scopedLesson(1, 1L, 10L, 100L, scopedRoom(1, 2L, 10L, 100L)))
            .hasNoImpact();
    }

    @Test
    void roomScopedToMatchingDepartmentAndYear_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomScopeMatch)
            .given(scopedLesson(1, 1L, 10L, null, scopedRoom(1, 1L, 10L, null)))
            .hasNoImpact();
    }

    @Test
    void roomScopedToDifferentYear_isPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomScopeMatch)
            .given(scopedLesson(1, 1L, 10L, null, scopedRoom(1, 1L, 11L, null)))
            .penalizesBy(1);
    }

    @Test
    void roomScopedToSameSection_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomScopeMatch)
            .given(scopedLesson(1, 1L, 10L, 100L, scopedRoom(1, 1L, 10L, 100L)))
            .hasNoImpact();
    }

    @Test
    void roomScopedToDifferentSection_isPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomScopeMatch)
            .given(scopedLesson(1, 1L, 10L, 100L, scopedRoom(1, 1L, 10L, 101L)))
            .penalizesBy(1);
    }

    @Test
    void unassignedLesson_isIgnored() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomScopeMatch)
            .given(scopedLesson(1, 1L, 10L, 100L, null))
            .hasNoImpact();
    }
}
