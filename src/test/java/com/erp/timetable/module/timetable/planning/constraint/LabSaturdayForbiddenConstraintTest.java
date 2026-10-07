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
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.room;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.subject;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.window;

/**
 * Tests for {@code TimetableConstraintProvider#labSaturdayForbidden} — the
 * Timefold translation of the Greedy rule that LAB sessions are NEVER scheduled
 * on Saturday ({@code LAB_DAYS}). Mirrors the Greedy engine, which offers only
 * the five non-Saturday working days to every practical placement.
 *
 * <p>The rule only ever fires for a PLACED lab lesson whose window is a Saturday
 * teaching slot. Theory lessons on Saturday, lab lessons on any other day, and
 * unassigned lab lessons are all exempt (unassigned lessons hold no window, so
 * they never create a phantom Saturday violation).
 */
class LabSaturdayForbiddenConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableFaculty PROF = faculty(1);
    private static final PlannableRoom LAB_ROOM = room(1, "LAB", 60);
    private static final PlannableSubject LAB = subject(1, "PHY201L", "LAB", 1L);
    private static final PlannableSubject THEORY = subject(2, "PHY201", "THEORY", 1L);

    @Test
    void labOnSaturday_penalizesByOne() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::labSaturdayForbidden)
            .given(labLesson(1, saturday(1)))
            .penalizesBy(1);
    }

    @Test
    void labOnAnyNonSaturdayDay_notPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::labSaturdayForbidden)
            .given(
                labLesson(1, window(1, "MON", 1)),
                labLesson(2, window(2, "TUE", 2)),
                labLesson(3, window(3, "WED", 3)),
                labLesson(4, window(4, "THU", 4)),
                labLesson(5, window(5, "FRI", 5)))
            .hasNoImpact();
    }

    @Test
    void theoryOnSaturday_notPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::labSaturdayForbidden)
            .given(
                lesson(1, 1, PROF, THEORY, room(2, "LECTURE_HALL", 60), saturday(1)),
                lesson(2, 1, PROF, THEORY, room(2, "LECTURE_HALL", 60), saturday(2)))
            .hasNoImpact();
    }

    @Test
    void unassignedLabLesson_notPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::labSaturdayForbidden)
            .given(labLessonUnassigned(1))
            .hasNoImpact();
    }

    @Test
    void mixedWeek_twoSaturdayLabs_penalizesByTwo() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::labSaturdayForbidden)
            .given(
                labLesson(1, saturday(1)),
                labLesson(2, saturday(2)),
                labLesson(3, window(3, "MON", 1)))
            .penalizesBy(2);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static PlannableTimeSlot saturday(int order) {
        return window(500 + order, "SAT", order);
    }

    private static PlanningLesson labLesson(long id, PlannableTimeSlot timeSlot) {
        return lesson(id, 1, PROF, LAB, LAB_ROOM, timeSlot);
    }

    private static PlanningLesson labLessonUnassigned(long id) {
        return lesson(id, 1, PROF, LAB, null, null);
    }
}
