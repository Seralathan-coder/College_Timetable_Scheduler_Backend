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
 * Tests for {@code TimetableConstraintProvider#facultyAssignedSubject} — the
 * Timefold translation of the Greedy {@code FacultyAssignedSubjectConstraint}
 * (direct subject assignment, assigned-subject-code list, or specialization
 * keyword match; an unassigned subject is allowed).
 */
class FacultyAssignedSubjectConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableRoom ROOM = room(1);

    private static PlanningLesson subLesson(long lessonId, PlannableFaculty professor, PlannableSubject subj) {
        return lesson(lessonId, 1, professor, subj, ROOM, window(lessonId, "MON", 1));
    }

    @Test
    void subjectAssignedToFaculty_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAssignedSubject)
            .given(subLesson(1, faculty(1), subject(1, "PHY101", "THEORY", 1L)))
            .hasNoImpact();
    }

    @Test
    void subjectAssignedToAnotherFaculty_isPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAssignedSubject)
            .given(subLesson(1, faculty(1), subject(1, "PHY101", "THEORY", 9L)))
            .penalizesBy(1);
    }

    @Test
    void assignedSubjectCodes_match_caseInsensitive() {
        PlannableFaculty professor = faculty(1);
        professor.setAssignedSubjectCodes("PHY101, MAT201");
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAssignedSubject)
            .given(subLesson(1, professor, subject(1, "phy101", "THEORY", 9L)))
            .hasNoImpact();
    }

    @Test
    void assignedSubjectCodes_noMatch_isPenalized() {
        PlannableFaculty professor = faculty(1);
        professor.setAssignedSubjectCodes("CHE201");
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAssignedSubject)
            .given(subLesson(1, professor, subject(1, "PHY101", "THEORY", 9L)))
            .penalizesBy(1);
    }

    @Test
    void specializationMatch_subjectNameContainsSpecialization() {
        PlannableFaculty professor = faculty(1);
        professor.setSpecialization("computer");
        PlannableSubject cs = subject(1, "CS101", "THEORY", 9L);
        cs.setSubjectName("Computer Networks");
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAssignedSubject)
            .given(subLesson(1, professor, cs))
            .hasNoImpact();
    }

    @Test
    void specializationMatch_specializationContainsNamePrefix() {
        PlannableFaculty professor = faculty(1);
        professor.setSpecialization("Comp Sci");
        PlannableSubject cs = subject(1, "CS101", "THEORY", 9L);
        cs.setSubjectName("Comp");
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAssignedSubject)
            .given(subLesson(1, professor, cs))
            .hasNoImpact();
    }

    @Test
    void unassignedSubject_isAllowed() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAssignedSubject)
            .given(subLesson(1, faculty(1), subject(1, "PHY101", "THEORY", null)))
            .hasNoImpact();
    }

    @Test
    void unassignedSubjectWithNonMatchingCodes_isAllowed() {
        PlannableFaculty professor = faculty(1);
        professor.setAssignedSubjectCodes("CHE201");
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAssignedSubject)
            .given(subLesson(1, professor, subject(1, "PHY101", "THEORY", null)))
            .hasNoImpact();
    }

    @Test
    void nullSubject_isIgnored() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAssignedSubject)
            .given(lesson(1, 1, faculty(1), null, ROOM, window(1, "MON", 1)))
            .hasNoImpact();
    }

    @Test
    void missingFaculty_isIgnored() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAssignedSubject)
            .given(lesson(1, 1, null, subject(1, "PHY101", "THEORY", null), ROOM, window(1, "MON", 1)))
            .hasNoImpact();
    }

    @Test
    void twoViolatingLessons_penalizeByTwo() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAssignedSubject)
            .given(
                subLesson(1, faculty(1), subject(1, "PHY101", "THEORY", 9L)),
                subLesson(2, faculty(2), subject(2, "MAT201", "THEORY", 8L)))
            .penalizesBy(2);
    }
}
