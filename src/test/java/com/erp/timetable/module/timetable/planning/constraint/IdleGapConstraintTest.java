package com.erp.timetable.module.timetable.planning.constraint;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import com.erp.timetable.module.timetable.planning.model.PlannableFaculty;
import com.erp.timetable.module.timetable.planning.model.PlannableSubject;
import com.erp.timetable.module.timetable.planning.model.PlannableTimeSlot;
import com.erp.timetable.module.timetable.planning.model.PlanningLesson;
import com.erp.timetable.module.timetable.planning.model.SchedulingSolution;
import org.junit.jupiter.api.Test;

import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.faculty;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.room;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.window;
import static com.erp.timetable.module.timetable.planning.constraint.TimetableConstraintProvider.UNASSIGNED_LESSON_WEIGHT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6B — {@code TimetableConstraintProvider#idleGap} (soft).
 *
 * <p>For each faculty member and day, every non-break window strictly between
 * the faculty's first and last assigned teaching period that holds no assigned
 * lesson of that faculty costs ONE_SOFT. Free periods are deliberately not
 * penalised uniformly:
 * <ul>
 *   <li>free windows before the first lesson or after the last lesson — 0;</li>
 *   <li>break windows — 0 (break slots are never materialised as
 *       {@link PlannableTimeSlot} problem facts);</li>
 *   <li>days with a single teaching period — 0;</li>
 *   <li>unassigned lessons — never an idle penalty of their own (their missing
 *       window is counted once, as a free window between the faculty's actual
 *       assigned teaching periods).</li>
 * </ul>
 *
 * <p>Score direction: the penalty grows with the number of internal free
 * windows, so a compacted schedule outscores a gapped one. As with the
 * daily-hours and consecutive-teaching rules, lessons of one faculty-day must
 * reuse a single {@link PlannableFaculty} instance (grouping is by instance).
 */
class IdleGapConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableFaculty PROF = faculty(1);

    // P1 Teaching, P2 Teaching, P3 Teaching → idle gap = 0
    @Test
    void consecutiveTeaching_hasNoIdleGap() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::idleGap)
            .given(
                teachingLesson(1, 1, PROF, mon(1)),
                teachingLesson(2, 1, PROF, mon(2)),
                teachingLesson(3, 1, PROF, mon(3)),
                mon(1), mon(2), mon(3))
            .hasNoImpact();
    }

    // P1 Teaching, P2 Free, P3 Teaching → idle gap = 1
    @Test
    void singleFreePeriodBetweenLessons_penalizesByOne() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::idleGap)
            .given(
                teachingLesson(1, 1, PROF, mon(1)),
                teachingLesson(2, 1, PROF, mon(3)),
                mon(1), mon(2), mon(3))
            .penalizesBy(1);
    }

    // P1 Teaching, P2 Free, P3 Free, P4 Teaching → idle gap = 2
    @Test
    void twoFreePeriodsBetweenLessons_penalizeByTwo() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::idleGap)
            .given(
                teachingLesson(1, 1, PROF, mon(1)),
                teachingLesson(2, 1, PROF, mon(4)),
                mon(1), mon(2), mon(3), mon(4))
            .penalizesBy(2);
    }

    // P1 Free, P2 Teaching, P3 Teaching → idle gap = 0
    @Test
    void freePeriodBeforeFirstLesson_notPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::idleGap)
            .given(
                teachingLesson(1, 1, PROF, mon(2)),
                teachingLesson(2, 1, PROF, mon(3)),
                mon(1), mon(2), mon(3))
            .hasNoImpact();
    }

    // P1 Teaching, P2 Teaching, P3 Free → idle gap = 0
    @Test
    void freePeriodAfterLastLesson_notPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::idleGap)
            .given(
                teachingLesson(1, 1, PROF, mon(1)),
                teachingLesson(2, 1, PROF, mon(2)),
                mon(1), mon(2), mon(3))
            .hasNoImpact();
    }

    // P1 Teaching, P2 Break, P3 Teaching → idle gap = 0
    @Test
    void breakWindowBetweenLessons_notPenalized() {
        // Slot order 2 is a break window: like the real mapper, it is excluded
        // from the PlannableTimeSlot facts, so it can never be an idle window.
        constraintVerifier.verifyThat(TimetableConstraintProvider::idleGap)
            .given(
                teachingLesson(1, 1, PROF, mon(1)),
                teachingLesson(2, 1, PROF, mon(3)),
                mon(1), mon(3))
            .hasNoImpact();
    }

    // P1 Teaching, P2 Free, P3 Unassigned lesson, P4 Teaching → the unassigned
    // lesson creates no idle penalty of its own; P2 and P3 are free windows.
    @Test
    void unassignedLesson_createsNoIdlePenaltyOfItsOwn() {
        // Idle view: the unassigned lesson (null window) adds no teaching period
        // and no idle window; only P2 and P3 (free between the assigned P1/P4)
        // count.
        constraintVerifier.verifyThat(TimetableConstraintProvider::idleGap)
            .given(
                teachingLesson(1, 1, PROF, mon(1)),
                teachingLesson(2, 1, PROF, mon(4)),
                unassignedLesson(3, 1, PROF),
                mon(1), mon(2), mon(3), mon(4))
            .penalizesBy(2);

        // Full score: the same solution must not charge the unassigned lesson a
        // second time — exactly UNASSIGNED_LESSON_WEIGHT extra soft points on
        // top of the two idle windows.
        HardSoftScore full = constraintVerifier.verifyThat()
            .given(
                teachingLesson(1, 1, PROF, mon(1)),
                teachingLesson(2, 1, PROF, mon(4)),
                unassignedLesson(3, 1, PROF),
                mon(1), mon(2), mon(3), mon(4))
            .getScore();
        assertEquals(HardSoftScore.of(0, -(2 + UNASSIGNED_LESSON_WEIGHT)), full);
    }

    // Gaps are counted per faculty member and per day, never shared.
    @Test
    void gapsAreComputedPerFacultyAndPerDay() {
        PlannableFaculty profOne = faculty(1);
        PlannableFaculty profTwo = faculty(2);
        constraintVerifier.verifyThat(TimetableConstraintProvider::idleGap)
            .given(
                teachingLesson(1, 1, profOne, mon(1)),
                teachingLesson(2, 1, profOne, mon(3)),   // faculty 1: one MON gap
                teachingLesson(3, 2, profTwo, mon(1)),
                teachingLesson(4, 2, profTwo, mon(3)),   // faculty 2: one MON gap
                teachingLesson(5, 3, profOne, tue(1)),   // faculty 1: single TUE lesson → 0
                mon(1), mon(2), mon(3),
                tue(1))
            .penalizesBy(2);
    }

    // A day with a single teaching period never fires.
    @Test
    void singleLessonDay_hasNoIdleGap() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::idleGap)
            .given(
                teachingLesson(1, 1, PROF, mon(2)),
                mon(1), mon(2), mon(3))
            .hasNoImpact();
    }

    // Score direction: each extra internal free window worsens the soft score.
    @Test
    void scoreDirection_compactScheduleOutscoresGappedOne() {
        HardSoftScore compact = scoreOfGapSchedule(1, 2);   // P1,P2 → 0
        HardSoftScore oneGap = scoreOfGapSchedule(1, 3);    // P1,P3 → 1
        HardSoftScore twoGaps = scoreOfGapSchedule(1, 4);   // P1,P4 → 2

        assertEquals(HardSoftScore.of(0, 0), compact);
        assertEquals(HardSoftScore.of(0, -1), oneGap);
        assertEquals(HardSoftScore.of(0, -2), twoGaps);

        assertTrue(compact.compareTo(oneGap) > 0,
            "a compact schedule must outscore a one-gap schedule");
        assertTrue(oneGap.compareTo(twoGaps) > 0,
            "a one-gap schedule must outscore a two-gap schedule");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private HardSoftScore scoreOfGapSchedule(int firstOrder, int lastOrder) {
        return constraintVerifier.verifyThat()
            .given(
                teachingLesson(1, 1, PROF, mon(firstOrder)),
                teachingLesson(2, 1, PROF, mon(lastOrder)),
                mon(1), mon(2), mon(3), mon(4))
            .getScore();
    }

    private static PlannableTimeSlot mon(int order) {
        return window(order, "MON", order);
    }

    private static PlannableTimeSlot tue(int order) {
        return window(100 + order, "TUE", order);
    }

    private static PlanningLesson teachingLesson(long id, long sectionId, PlannableFaculty professor,
            PlannableTimeSlot timeSlot) {
        // One subject per lesson: the total-score checks in this class are meant
        // to isolate idle-gap + unassigned effects, so no two lessons may share
        // a subject (which would additionally fire the subject-distribution
        // objective, Phase 6C).
        PlannableSubject subject = ConstraintTestFixtures.subject(
            10 + id, "SUB" + id,
            "THEORY", professor.getFacultyId());
        return ConstraintTestFixtures.lesson(id, sectionId, professor, subject, room(1), timeSlot);
    }

    private static PlanningLesson unassignedLesson(long id, long sectionId, PlannableFaculty professor) {
        return ConstraintTestFixtures.lesson(id, sectionId, professor,
            ConstraintTestFixtures.subject(1, "PHY101", "THEORY", professor.getFacultyId()), room(1), null);
    }
}
