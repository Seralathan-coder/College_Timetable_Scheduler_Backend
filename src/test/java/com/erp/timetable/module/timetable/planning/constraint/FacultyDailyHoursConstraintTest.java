package com.erp.timetable.module.timetable.planning.constraint;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import com.erp.timetable.module.timetable.planning.model.PlannableFaculty;
import com.erp.timetable.module.timetable.planning.model.PlannableRoom;
import com.erp.timetable.module.timetable.planning.model.PlannableSubject;
import com.erp.timetable.module.timetable.planning.model.PlannableTimeSlot;
import com.erp.timetable.module.timetable.planning.model.PlanningLesson;
import com.erp.timetable.module.timetable.planning.model.SchedulingSolution;
import org.junit.jupiter.api.Test;

import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.faculty;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.lesson;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.occupancyFact;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.room;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.subject;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.window;

/**
 * Tests for {@code TimetableConstraintProvider#facultyDailyHoursLimit} — the
 * Timefold translation of the Greedy {@code FacultyDailyHoursConstraint}
 * (cap = min(college-wide 5, faculty maxDailyHours), default 5; each excess
 * period costs one hard point).
 */
class FacultyDailyHoursConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableRoom ROOM = room(1);
    private static final PlannableSubject SUBJECT = subject(1, "PHY101", "THEORY", 1L);

    private static PlanningLesson monLesson(int index, PlannableFaculty professor) {
        return lesson(index, 1, professor, SUBJECT, ROOM, window(index, "MON", index));
    }

    @Test
    void oneLessonBelowCap_isNotPenalized() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(monLesson(1, professor))
            .hasNoImpact();
    }

    @Test
    void fourLessonsSameDay_belowCollegeWideCap_isNotPenalized() {
        PlannableFaculty professor = faculty(1, 6, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor),
                monLesson(3, professor), monLesson(4, professor))
            .hasNoImpact();
    }

    @Test
    void fiveLessonsSameDay_atCollegeWideCap_isNotPenalized() {
        PlannableFaculty professor = faculty(1, 6, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor), monLesson(3, professor),
                monLesson(4, professor), monLesson(5, professor))
            .hasNoImpact();
    }

    @Test
    void sixLessonsSameDay_exceedsCollegeWideCap_penalizesByOne() {
        PlannableFaculty professor = faculty(1, 6, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor), monLesson(3, professor),
                monLesson(4, professor), monLesson(5, professor), monLesson(6, professor))
            .penalizesBy(1);
    }

    @Test
    void sevenLessonsSameDay_exceedsCollegeWideCap_penalizesByTwo() {
        PlannableFaculty professor = faculty(1, 6, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor), monLesson(3, professor),
                monLesson(4, professor), monLesson(5, professor), monLesson(6, professor),
                monLesson(7, professor))
            .penalizesBy(2);
    }

    @Test
    void eightLessonsSameDay_penalizesByThree() {
        PlannableFaculty professor = faculty(1, 6, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor), monLesson(3, professor),
                monLesson(4, professor), monLesson(5, professor), monLesson(6, professor),
                monLesson(7, professor), monLesson(8, professor))
            .penalizesBy(3);
    }

    @Test
    void facultyOwnCapThree_threeLessonsAtCap_isNotPenalized() {
        PlannableFaculty professor = faculty(1, 3, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor), monLesson(3, professor))
            .hasNoImpact();
    }

    @Test
    void facultyOwnCapThree_fourLessons_penalizesByOne() {
        PlannableFaculty professor = faculty(1, 3, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor),
                monLesson(3, professor), monLesson(4, professor))
            .penalizesBy(1);
    }

    @Test
    void facultyOwnCapAboveCollegeWide_isCappedAtFive() {
        PlannableFaculty professor = faculty(1, 10, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor), monLesson(3, professor),
                monLesson(4, professor), monLesson(5, professor), monLesson(6, professor),
                monLesson(7, professor), monLesson(8, professor))
            .penalizesBy(3);
    }

    @Test
    void facultyOwnCapUnset_defaultsToCollegeWideCap() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor), monLesson(3, professor),
                monLesson(4, professor), monLesson(5, professor), monLesson(6, professor),
                monLesson(7, professor), monLesson(8, professor))
            .penalizesBy(3);
    }

    @Test
    void lessonsSpreadAcrossDays_areNotPenalized() {
        PlannableFaculty professor = faculty(1, 6, 24);
        String[] days = {"MON", "TUE", "WED", "THU", "FRI"};
        PlanningLesson[] lessons = new PlanningLesson[5];
        for (int i = 0; i < days.length; i++) {
            lessons[i] = lesson(i + 1, 1, professor, SUBJECT, ROOM, window(i + 1, days[i], 1));
        }
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(lessons)
            .hasNoImpact();
    }

    @Test
    void unscheduledLessons_areIgnored() {
        PlannableFaculty professor = faculty(1, 6, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                lesson(1, 1, professor, SUBJECT, null, null),
                lesson(2, 1, professor, SUBJECT, null, null),
                lesson(3, 1, professor, SUBJECT, null, null),
                lesson(4, 1, professor, SUBJECT, null, null),
                lesson(5, 1, professor, SUBJECT, null, null))
            .hasNoImpact();
    }

    @Test
    void missingFacultyLessons_areIgnored() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                lesson(1, 1, null, SUBJECT, ROOM, window(1, "MON", 1)),
                lesson(2, 1, null, SUBJECT, ROOM, window(2, "MON", 2)))
            .hasNoImpact();
    }

    // ── cross-timetable awareness (Phase 7 parity with the Greedy engine) ──
    // The daily cap applies to the faculty's total load across EVERY timetable:
    // each OccupancyFact of the same faculty and day (one per foreign entry)
    // counts as one extra period of that day's load.

    @Test
    void foreignLoad_combinedTotalAtCap_isNotPenalized() {
        PlannableFaculty professor = faculty(1, 6, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor), monLesson(3, professor),
                occupancyFact(1L, 1L, "MON", 10),
                occupancyFact(1L, 1L, "MON", 11))
            .hasNoImpact();
    }

    @Test
    void foreignLoad_pushesCombinedTotalOverCap_penalizesByExcess() {
        PlannableFaculty professor = faculty(1, 6, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor), monLesson(3, professor),
                occupancyFact(1L, 1L, "MON", 10),
                occupancyFact(1L, 1L, "MON", 11),
                occupancyFact(1L, 1L, "MON", 12))
            .penalizesBy(1);
    }

    @Test
    void ownAtCap_foreignLoadPushesOver_penalizesByCombinedExcess() {
        PlannableFaculty professor = faculty(1, 6, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor), monLesson(3, professor),
                monLesson(4, professor), monLesson(5, professor),
                occupancyFact(1L, 1L, "MON", 10),
                occupancyFact(1L, 1L, "MON", 11))
            .penalizesBy(2);
    }

    @Test
    void ownExceedsCap_withForeignLoad_penalizesOnlyOwnExcess() {
        PlannableFaculty professor = faculty(1, 6, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor), monLesson(3, professor),
                monLesson(4, professor), monLesson(5, professor), monLesson(6, professor),
                occupancyFact(1L, 1L, "MON", 10),
                occupancyFact(1L, 1L, "MON", 11),
                occupancyFact(1L, 1L, "MON", 12))
            .penalizesBy(1);
    }

    @Test
    void foreignLoadOnly_withoutOwnLessons_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                occupancyFact(1L, 1L, "MON", 10),
                occupancyFact(1L, 1L, "MON", 11),
                occupancyFact(1L, 1L, "MON", 12))
            .hasNoImpact();
    }

    @Test
    void foreignLoadOfOtherFacultyOrOtherDay_isIgnored() {
        PlannableFaculty professor = faculty(1, 6, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor), monLesson(3, professor),
                occupancyFact(2L, 1L, "MON", 10),
                occupancyFact(1L, 1L, "TUE", 11))
            .hasNoImpact();
    }

    @Test
    void ownCapLowerThanCollegeWide_withForeignLoad_penalizesByCombinedExcess() {
        PlannableFaculty professor = faculty(1, 3, 24);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyDailyHoursLimit)
            .given(
                monLesson(1, professor), monLesson(2, professor), monLesson(3, professor),
                occupancyFact(1L, 1L, "MON", 10),
                occupancyFact(1L, 1L, "MON", 11))
            .penalizesBy(2);
    }
}
