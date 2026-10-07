package com.erp.timetable.module.timetable.planning.constraint;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
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
 * Tests for {@code TimetableConstraintProvider#roomTypeMatch} — the Timefold
 * translation of the Greedy {@code RoomTypeConstraint} (a practical lesson —
 * component flag isLab — needs a LAB room; a theory lesson needs a non-LAB
 * room).
 */
class RoomTypeConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableSubject LAB = subject(1, "PHY-LAB", "LAB", 1L);
    private static final PlannableSubject THEORY = subject(2, "PHY101", "THEORY", 2L);

    private static PlanningLesson typeLesson(long lessonId, long sectionId,
            PlannableSubject subj, String roomType) {
        return lesson(lessonId, sectionId, faculty(lessonId), subj,
            room(lessonId, roomType, 60), window(lessonId, "MON", 1));
    }

    @Test
    void labSubjectInLabRoom_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomTypeMatch)
            .given(typeLesson(1, 1, LAB, "LAB"))
            .hasNoImpact();
    }

    @Test
    void labSubjectInLectureRoom_isPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomTypeMatch)
            .given(typeLesson(1, 1, LAB, "LECTURE_HALL"))
            .penalizesBy(1);
    }

    @Test
    void theorySubjectInLectureRoom_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomTypeMatch)
            .given(typeLesson(1, 1, THEORY, "LECTURE_HALL"))
            .hasNoImpact();
    }

    @Test
    void theorySubjectInLabRoom_isPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomTypeMatch)
            .given(typeLesson(1, 1, THEORY, "LAB"))
            .penalizesBy(1);
    }

    @Test
    void seminarRoomForTheory_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomTypeMatch)
            .given(typeLesson(1, 1, THEORY, "SEMINAR_ROOM"))
            .hasNoImpact();
    }

    @Test
    void nullRoom_isIgnored() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomTypeMatch)
            .given(lesson(1, 1, faculty(1), THEORY, null, null))
            .hasNoImpact();
    }

    @Test
    void nullSubject_isIgnored() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomTypeMatch)
            .given(lesson(1, 1, faculty(1), null, room(1, "LAB", 60), window(1, "MON", 1)))
            .hasNoImpact();
    }

    @Test
    void mixedValidAndInvalid_penalizeOnlyMismatches() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomTypeMatch)
            .given(
                typeLesson(1, 1, LAB, "LAB"),
                typeLesson(2, 2, LAB, "LECTURE_HALL"),
                typeLesson(3, 3, THEORY, "LAB"))
            .penalizesBy(2);
    }
}
