package com.erp.timetable.module.timetable.planning.constraint;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import com.erp.timetable.module.timetable.planning.model.PlannableFaculty;
import com.erp.timetable.module.timetable.planning.model.PlannableRoom;
import com.erp.timetable.module.timetable.planning.model.PlannableSubject;
import com.erp.timetable.module.timetable.planning.model.PlannableTimeSlot;
import com.erp.timetable.module.timetable.planning.model.PlanningLesson;
import com.erp.timetable.module.timetable.planning.model.SchedulingSolution;
import org.junit.jupiter.api.Test;

import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.availabilityFact;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.faculty;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.lesson;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.room;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.subject;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.window;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@code TimetableConstraintProvider#facultyAvailability} — the
 * Timefold translation of the Greedy {@code FacultyAvailabilityConstraint}
 * (null faculty -> reject; LEAVE status -> reject; any BLOCKED/BUSY slot ->
 * reject).
 */
class FacultyAvailabilityConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableRoom ROOM = room(1);
    private static final PlannableTimeSlot MON = window(1, "MON", 1);
    private static final PlannableSubject SUBJECT = subject(1, "PHY101", "THEORY", 1L);

    // ------------------------------- LEAVE -------------------------------

    @Test
    void leaveFaculty_scheduledLesson_isPenalized() {
        PlannableFaculty onLeave = faculty(1);
        onLeave.setStatus("LEAVE");
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAvailability)
            .given(lesson(1, 1, onLeave, SUBJECT, ROOM, MON))
            .penalizesBy(1);
    }

    @Test
    void leaveFaculty_caseInsensitive_isPenalized() {
        PlannableFaculty onLeave = faculty(1);
        onLeave.setStatus("leave");
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAvailability)
            .given(lesson(1, 1, onLeave, SUBJECT, ROOM, MON))
            .penalizesBy(1);
    }

    @Test
    void availableFaculty_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAvailability)
            .given(lesson(1, 1, faculty(1), SUBJECT, ROOM, MON))
            .hasNoImpact();
    }

    @Test
    void leaveFaculty_unscheduledLesson_isIgnored() {
        PlannableFaculty onLeave = faculty(1);
        onLeave.setStatus("LEAVE");
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAvailability)
            .given(lesson(1, 1, onLeave, SUBJECT, null, null))
            .hasNoImpact();
    }

    @Test
    void leaveFaculty_withMatchingBlockedFact_isPenalizedOnce() {
        PlannableFaculty onLeave = faculty(1);
        onLeave.setStatus("LEAVE");
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAvailability)
            .given(
                lesson(1, 1, onLeave, SUBJECT, ROOM, MON),
                availabilityFact(1, "MON", 1, "BLOCKED"))
            .penalizesBy(1);
    }

    // --------------------------- BLOCKED / BUSY --------------------------

    @Test
    void blockedFact_matchingLesson_isPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAvailability)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                availabilityFact(1, "MON", 1, "BLOCKED"))
            .penalizesBy(1);
    }

    @Test
    void busyFact_matchingLesson_isPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAvailability)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                availabilityFact(1, "MON", 1, "BUSY"))
            .penalizesBy(1);
    }

    @Test
    void preferredFact_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAvailability)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                availabilityFact(1, "MON", 1, "PREFERRED"))
            .hasNoImpact();
    }

    @Test
    void availableFact_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAvailability)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                availabilityFact(1, "MON", 1, "AVAILABLE"))
            .hasNoImpact();
    }

    @Test
    void blockedFact_otherFaculty_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAvailability)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                availabilityFact(2, "MON", 1, "BLOCKED"))
            .hasNoImpact();
    }

    @Test
    void blockedFact_otherDay_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAvailability)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                availabilityFact(1, "TUE", 1, "BLOCKED"))
            .hasNoImpact();
    }

    @Test
    void blockedFact_otherSlot_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAvailability)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                availabilityFact(1, "MON", 2, "BLOCKED"))
            .hasNoImpact();
    }

    @Test
    void noAvailabilityFacts_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAvailability)
            .given(lesson(1, 1, faculty(1), SUBJECT, ROOM, MON))
            .hasNoImpact();
    }

    @Test
    void twoBlockedLessons_penalizeByTwo() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyAvailability)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                lesson(2, 2, faculty(2), subject(2, "MAT201", "THEORY", 2L),
                    room(2), window(2, "TUE", 1)),
                availabilityFact(1, "MON", 1, "BLOCKED"),
                availabilityFact(2, "TUE", 2, "BUSY"))
            .penalizesBy(2);
    }

    // ----------------------------- aggregate -----------------------------

    @Test
    void aggregate_leaveAndBlocked_sumBothPenalties() {
        PlannableFaculty onLeave = faculty(9);
        onLeave.setStatus("LEAVE");
        PlannableSubject forLeave = subject(9, "MAT201", "THEORY", 9L);
        HardSoftScore score = constraintVerifier.verifyThat()
            .given(
                lesson(1, 1, faculty(1), SUBJECT, ROOM, MON),
                lesson(2, 2, onLeave, forLeave, room(2), window(2, "TUE", 1)),
                availabilityFact(1, "MON", 1, "BLOCKED"))
            .getScore();

        assertEquals(HardSoftScore.of(-2, 0), score);
    }
}
