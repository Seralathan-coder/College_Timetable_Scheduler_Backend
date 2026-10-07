package com.erp.timetable.module.timetable.planning.constraint;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
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
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@code TimetableConstraintProvider#crossTimetableOccupancy} — the
 * Phase 7 hard rule that a lesson may not use a (faculty, day, slot) or
 * (room, day, slot) window already occupied by another timetable, mirroring the
 * Greedy engine's whole-college occupancy context. A lesson clashing on faculty
 * only, room only, or both counts as exactly one violation.
 */
class OccupancyConflictConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableRoom ROOM = room(1);
    private static final PlannableTimeSlot MON = window(1, "MON", 1);
    private static final PlannableSubject SUBJECT = subject(1, "PHY101", "THEORY", 1L);

    // ------------------------------ clashes ------------------------------

    @Test
    void noOccupancyFacts_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::crossTimetableOccupancy)
            .given(lesson(1, 1, faculty(1), SUBJECT, ROOM, MON))
            .hasNoImpact();
    }

    @Test
    void matchingFacultySameWindow_isPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::crossTimetableOccupancy)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                occupancyFact(1L, 99L, "MON", 1))
            .penalizesBy(1);
    }

    @Test
    void matchingRoomSameWindow_isPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::crossTimetableOccupancy)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                occupancyFact(99L, 1L, "MON", 1))
            .penalizesBy(1);
    }

    @Test
    void matchingFacultyAndRoom_isPenalizedOnce() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::crossTimetableOccupancy)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                occupancyFact(1L, 1L, "MON", 1))
            .penalizesBy(1);
    }

    @Test
    void lessonClashingWithTwoOtherTimetables_isPenalizedOnce() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::crossTimetableOccupancy)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                occupancyFact(1L, 50L, "MON", 1),
                occupancyFact(1L, 60L, "MON", 1))
            .penalizesBy(1);
    }

    @Test
    void differentFacultyAndRoom_sameWindow_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::crossTimetableOccupancy)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                occupancyFact(2L, 2L, "MON", 1))
            .hasNoImpact();
    }

    @Test
    void sameFacultyOtherDay_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::crossTimetableOccupancy)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, window(1, "TUE", 1)),
                occupancyFact(1L, 1L, "MON", 1))
            .hasNoImpact();
    }

    @Test
    void sameFacultyOtherSlot_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::crossTimetableOccupancy)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, window(2, "MON", 2)),
                occupancyFact(1L, 1L, "MON", 1))
            .hasNoImpact();
    }

    @Test
    void twoConflictingLessons_penalizeByTwo() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::crossTimetableOccupancy)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                lesson(2, 2, faculty(2), subject(2, "MAT201", "THEORY", 2L),
                    room(2), window(2, "TUE", 1)),
                occupancyFact(1L, 1L, "MON", 1),
                occupancyFact(2L, 2L, "TUE", 2))
            .penalizesBy(2);
    }

    @Test
    void unassignedLesson_matchingFact_isIgnored() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::crossTimetableOccupancy)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, null, null),
                occupancyFact(1L, 1L, "MON", 1))
            .hasNoImpact();
    }

    // ----------------------------- aggregate -----------------------------

    @Test
    void aggregate_facultyAndRoomClash_isExactlyOneHardPoint() {
        HardSoftScore score = constraintVerifier.verifyThat()
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                occupancyFact(1L, 1L, "MON", 1))
            .getScore();

        assertEquals(HardSoftScore.of(-1, 0), score);
    }
}
