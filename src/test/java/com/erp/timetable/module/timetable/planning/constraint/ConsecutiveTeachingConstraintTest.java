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
 * Tests for {@code TimetableConstraintProvider#consecutiveTeachingRule}.
 *
 * <p>College policy now allows faculty to teach back-to-back periods, so the
 * rule (the old max-2-consecutive / min-2-free-gap restriction mirrored from
 * the Greedy {@code ConsecutiveTeachingConstraint}) must NEVER penalise, no
 * matter how the day's teaching slots are grouped. The faculty daily-hour cap
 * (see {@code facultyDailyHoursLimit}) remains the binding daily limit.
 */
class ConsecutiveTeachingConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableRoom ROOM = room(1);
    private static final PlannableSubject THEORY = subject(1, "PHY101", "THEORY", 1L);

    private static PlanningLesson theoryLesson(long lessonId, PlannableFaculty professor, int slotOrder) {
        return lesson(lessonId, 1, professor, THEORY, ROOM, window(lessonId, "MON", slotOrder));
    }

    @Test
    void singlePeriod_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::consecutiveTeachingRule)
            .given(theoryLesson(1, faculty(1), 1))
            .hasNoImpact();
    }

    @Test
    void threeBackToBackTheoryPeriods_areAllowed() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::consecutiveTeachingRule)
            .given(
                theoryLesson(1, professor, 1),
                theoryLesson(2, professor, 2),
                theoryLesson(3, professor, 3))
            .hasNoImpact();
    }

    @Test
    void fiveBackToBackTheoryPeriods_areAllowed() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::consecutiveTeachingRule)
            .given(
                theoryLesson(1, professor, 1),
                theoryLesson(2, professor, 2),
                theoryLesson(3, professor, 3),
                theoryLesson(4, professor, 4),
                theoryLesson(5, professor, 5))
            .hasNoImpact();
    }

    @Test
    void twoBlocksWithNoFreeGap_areAllowed() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::consecutiveTeachingRule)
            .given(
                theoryLesson(1, professor, 1),
                theoryLesson(2, professor, 2),
                theoryLesson(3, professor, 3))
            .hasNoImpact();
    }

    @Test
    void labBlockThenAdjacentTheory_isAllowed() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::consecutiveTeachingRule)
            .given(
                lesson(1, 1, professor, subject(2, "PHY-LAB", "LAB", 1L), ROOM, window(1, "MON", 1)),
                lesson(2, 1, professor, subject(2, "PHY-LAB", "LAB", 1L), ROOM, window(2, "MON", 2)),
                theoryLesson(3, professor, 3))
            .hasNoImpact();
    }

    @Test
    void differentDays_areSeparateGroups() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::consecutiveTeachingRule)
            .given(
                theoryLesson(1, professor, 1),
                theoryLesson(2, professor, 2),
                lesson(3, 1, professor, THEORY, ROOM, window(3, "TUE", 3)))
            .hasNoImpact();
    }

    @Test
    void unscheduledLessons_areIgnored() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::consecutiveTeachingRule)
            .given(
                lesson(1, 1, professor, THEORY, null, null),
                lesson(2, 1, professor, THEORY, null, null))
            .hasNoImpact();
    }
}
