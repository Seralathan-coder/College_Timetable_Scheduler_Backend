package com.erp.timetable.module.timetable.planning.constraint;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import com.erp.timetable.module.timetable.planning.model.PlannableFaculty;
import com.erp.timetable.module.timetable.planning.model.PlannableRoom;
import com.erp.timetable.module.timetable.planning.model.PlannableSubject;
import com.erp.timetable.module.timetable.planning.model.PlanningLesson;
import com.erp.timetable.module.timetable.planning.model.SchedulingSolution;
import org.junit.jupiter.api.Test;

import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.faculty;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.lesson;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.room;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.subject;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.window;

/**
 * Tests for {@code TimetableConstraintProvider#departmentPermission} — the
 * Timefold translation of the Greedy {@code DepartmentPermissionConstraint}
 * (primary department id match, or a teaching-department token matching the
 * target department id or the faculty's department name).
 */
class DepartmentPermissionConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableRoom ROOM = room(1);
    private static final PlannableSubject SUBJECT = subject(1, "PHY101", "THEORY", null);
    private static final PlannableSubject ASSIGNED_SUBJECT = subject(1, "PHY101", "THEORY", 1L);

    private static PlanningLesson lessonInDept(long lessonId, PlannableFaculty professor, Long departmentId) {
        return lesson(lessonId, 1, professor, SUBJECT, departmentId, 40, ROOM, window(lessonId, "MON", 1));
    }

    private static PlanningLesson assignedLessonInDept(long lessonId, PlannableFaculty professor, Long departmentId) {
        return lesson(lessonId, 1, professor, ASSIGNED_SUBJECT, departmentId, 40, ROOM, window(lessonId, "MON", 1));
    }

    @Test
    void facultyInPrimaryDepartment_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::departmentPermission)
            .given(lessonInDept(1, faculty(1), 1L))
            .hasNoImpact();
    }

    @Test
    void facultyFromDifferentDepartment_isPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::departmentPermission)
            .given(lessonInDept(1, faculty(1), 2L))
            .penalizesBy(1);
    }

    @Test
    void teachingDepartmentTokenById_matches() {
        PlannableFaculty shared = faculty(1);
        shared.setTeachingDepartments("2");
        constraintVerifier.verifyThat(TimetableConstraintProvider::departmentPermission)
            .given(lessonInDept(1, shared, 2L))
            .hasNoImpact();
    }

    @Test
    void teachingDepartmentTokenByName_matches_caseInsensitive() {
        PlannableFaculty shared = faculty(1);
        shared.setDepartmentName("Computer Science");
        shared.setTeachingDepartments("computer science");
        constraintVerifier.verifyThat(TimetableConstraintProvider::departmentPermission)
            .given(lessonInDept(1, shared, 99L))
            .hasNoImpact();
    }

    @Test
    void teachingDepartmentTokens_areTrimmed() {
        PlannableFaculty shared = faculty(1);
        shared.setTeachingDepartments(" 2 , 3 ");
        constraintVerifier.verifyThat(TimetableConstraintProvider::departmentPermission)
            .given(lessonInDept(1, shared, 2L))
            .hasNoImpact();
    }

    @Test
    void teachingDepartmentNoMatch_isPenalized() {
        PlannableFaculty shared = faculty(1);
        shared.setTeachingDepartments("5");
        constraintVerifier.verifyThat(TimetableConstraintProvider::departmentPermission)
            .given(lessonInDept(1, shared, 2L))
            .penalizesBy(1);
    }

    @Test
    void blankTeachingDepartments_isPenalized() {
        PlannableFaculty shared = faculty(1);
        shared.setTeachingDepartments("   ");
        constraintVerifier.verifyThat(TimetableConstraintProvider::departmentPermission)
            .given(lessonInDept(1, shared, 2L))
            .penalizesBy(1);
    }

    @Test
    void nullTeachingDepartments_isPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::departmentPermission)
            .given(lessonInDept(1, faculty(1), 2L))
            .penalizesBy(1);
    }

    @Test
    void primaryDepartmentMatch_takesPrecedence() {
        PlannableFaculty shared = faculty(1);
        shared.setTeachingDepartments("2");
        constraintVerifier.verifyThat(TimetableConstraintProvider::departmentPermission)
            .given(lessonInDept(1, shared, 1L))
            .hasNoImpact();
    }

    @Test
    void nullTargetDepartment_isIgnored() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::departmentPermission)
            .given(lessonInDept(1, faculty(1), null))
            .hasNoImpact();
    }

    @Test
    void missingFaculty_isIgnored() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::departmentPermission)
            .given(lesson(1, 1, null, SUBJECT, 2L, 40, ROOM, window(1, "MON", 1)))
            .hasNoImpact();
    }

    @Test
    void explicitlyAssignedFacultyFromDifferentDepartment_isNotPenalized() {
        // ASSIGNED_SUBJECT's assignedFacultyId is 1, faculty(1) is the assigned
        // owner, but the lesson is taught for department 2 — the explicit
        // assignment overrides the department mismatch.
        constraintVerifier.verifyThat(TimetableConstraintProvider::departmentPermission)
            .given(assignedLessonInDept(1, faculty(1), 2L))
            .hasNoImpact();
    }

    @Test
    void nonAssignedFacultyFromDifferentDepartment_isStillPenalized() {
        // ASSIGNED_SUBJECT is assigned to faculty 1, but faculty 2 teaches for
        // department 99 (outside faculty 2's own department): the exemption must
        // apply to the assigned faculty only.
        constraintVerifier.verifyThat(TimetableConstraintProvider::departmentPermission)
            .given(assignedLessonInDept(1, faculty(2), 99L))
            .penalizesBy(1);
    }
}
