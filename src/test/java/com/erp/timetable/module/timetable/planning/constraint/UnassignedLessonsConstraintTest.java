package com.erp.timetable.module.timetable.planning.constraint;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import com.erp.timetable.module.timetable.planning.model.PlannableFaculty;
import com.erp.timetable.module.timetable.planning.model.PlannableSubject;
import com.erp.timetable.module.timetable.planning.model.PlanningLesson;
import com.erp.timetable.module.timetable.planning.model.SchedulingSolution;
import org.junit.jupiter.api.Test;

import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.faculty;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.lesson;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.room;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.subject;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.window;
import static com.erp.timetable.module.timetable.planning.constraint.TimetableConstraintProvider.UNASSIGNED_LESSON_WEIGHT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6A — {@code TimetableConstraintProvider#unassignedLessons} (soft).
 *
 * <p>The first soft optimization: every {@link PlanningLesson} left unassigned
 * (null room or null (day, time slot) window) costs
 * {@value TimetableConstraintProvider#UNASSIGNED_LESSON_WEIGHT} soft points, so
 * the solver prefers to schedule a lesson whenever a legal placement exists.
 * Unassigned lessons must never fire a hard constraint, and hard rules must
 * stay hard when a placement is actually illegal.
 *
 * <p>The large weight is deliberate: it must dominate the one-point units of
 * the other soft constraints (IDLE_GAP, SUBJECT_DISTRIBUTION) so the solver
 * never drops a lesson just to polish those preferences — the complete 42/42
 * schedule must always outscore any schedule that leaves lessons unassigned.
 *
 * <p>Score direction: the penalty is monotonic in the unassigned count —
 * 0 unassigned (score {@code 0}) is better than 1 ({@code -1000}), which is
 * better than 2 ({@code -2000}), and so on.
 */
class UnassignedLessonsConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableSubject SUBJECT = subject(1, "PHY101", "THEORY", 1L);

    // ── dedicated constraint: UNASSIGNED_LESSON_WEIGHT per unassigned lesson ──

    @Test
    void assignedLesson_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::unassignedLessons)
            .given(lesson(1, 1, faculty(1), SUBJECT, room(1), window(1, "MON", 1)))
            .hasNoImpact();
    }

    @Test
    void lessonWithoutRoom_isPenalizedOnce() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::unassignedLessons)
            .given(lesson(1, 1, faculty(1), SUBJECT, null, window(1, "MON", 1)))
            .penalizesBy(1);
    }

    @Test
    void lessonWithoutTimeSlot_isPenalizedOnce() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::unassignedLessons)
            .given(lesson(1, 1, faculty(1), SUBJECT, room(1), null))
            .penalizesBy(1);
    }

    @Test
    void lessonWithoutRoomOrTimeSlot_isPenalizedOnce() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::unassignedLessons)
            .given(lesson(1, 1, faculty(1), SUBJECT, null, null))
            .penalizesBy(1);
    }

    @Test
    void twoUnassignedLessons_penalizeByTwo() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::unassignedLessons)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, null, null),
                lesson(2, 2, faculty(2), SUBJECT, null, null))
            .penalizesBy(2);
    }

    @Test
    void mixedAssignedAndUnassigned_onlyUnassignedIsPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::unassignedLessons)
            .given(
                lesson(1, 1, faculty(1), SUBJECT, room(1), window(1, "MON", 1)),
                lesson(2, 2, faculty(1), SUBJECT, null, null),
                lesson(3, 3, faculty(1), SUBJECT, null, null))
            .penalizesBy(2);
    }

    // ── score direction: 0 unassigned better than 1 better than 2 ... ──────

    @Test
    void scoreDirection_fewerUnassignedIsAlwaysBetter() {
        HardSoftScore zeroUnassigned = constraintVerifier.verifyThat()
            .given(
                lesson(1, 1, faculty(1), SUBJECT, room(1), window(1, "MON", 1)),
                lesson(2, 2, faculty(1), SUBJECT, room(1), window(2, "MON", 2)))
            .getScore();

        HardSoftScore oneUnassigned = constraintVerifier.verifyThat()
            .given(
                lesson(1, 1, faculty(1), SUBJECT, room(1), window(1, "MON", 1)),
                lesson(2, 2, faculty(1), SUBJECT, null, null))
            .getScore();

        HardSoftScore twoUnassigned = constraintVerifier.verifyThat()
            .given(
                lesson(1, 1, faculty(1), SUBJECT, null, null),
                lesson(2, 2, faculty(1), SUBJECT, null, null))
            .getScore();

        assertEquals(HardSoftScore.ZERO, zeroUnassigned);
        assertEquals(HardSoftScore.of(0, -UNASSIGNED_LESSON_WEIGHT), oneUnassigned);
        assertEquals(HardSoftScore.of(0, -2 * UNASSIGNED_LESSON_WEIGHT), twoUnassigned);

        // Explicit ordering: 0 unassigned ≻ 1 unassigned ≻ 2 unassigned.
        assertTrue(zeroUnassigned.compareTo(oneUnassigned) > 0,
            "0 unassigned must score better than 1 unassigned");
        assertTrue(oneUnassigned.compareTo(twoUnassigned) > 0,
            "1 unassigned must score better than 2 unassigned");
        assertTrue(twoUnassigned.compareTo(HardSoftScore.of(0, -3 * UNASSIGNED_LESSON_WEIGHT)) > 0,
            "2 unassigned must score better than 3 unassigned");
    }

    // ── unassigned lessons never fire hard rules; hard rules stay hard ─────

    @Test
    void unassignedLesson_whosePlacementWouldViolateHardRules_onlyCostsSoft() {
        // A LEAVE-faculty LAB lesson: if it were assigned it would violate the
        // faculty-availability and room-type rules. Left unassigned it must
        // cost exactly two soft points and nothing hard.
        PlannableFaculty onLeave = faculty(1);
        onLeave.setStatus("LEAVE");
        PlannableSubject lab = subject(1, "PHY-LAB", "LAB", 1L);

        HardSoftScore unassigned = constraintVerifier.verifyThat()
            .given(lesson(1, 1, onLeave, lab, null, null))
            .getScore();

        assertEquals(HardSoftScore.of(0, -UNASSIGNED_LESSON_WEIGHT), unassigned,
            "an unassigned lesson may never produce a hard violation");

        // The same lesson forced into a LECTURE_HALL must still be hard —
        // hard constraints remain hard for illegal placements.
        HardSoftScore forced = constraintVerifier.verifyThat()
            .given(lesson(1, 1, onLeave, lab, room(1, "LECTURE_HALL", 60), window(1, "MON", 1)))
            .getScore();

        assertTrue(forced.hardScore() < 0,
            "hard rules must stay hard when the placement is illegal: " + forced);
    }
}
