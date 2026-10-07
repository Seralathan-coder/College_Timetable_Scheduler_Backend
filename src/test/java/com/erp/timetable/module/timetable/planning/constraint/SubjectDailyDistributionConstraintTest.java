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
 * {@code TimetableConstraintProvider#subjectDailyPeriodLimit} and
 * {@code #sectionDailyPairLimit} — the two HARD rules behind the NORMAL-subject
 * distribution rule, kept in lockstep with the Greedy engine's
 * {@code isAnotherTheoryPairPlacedOnDay} guard and the 2-period daily ceiling.
 *
 * <pre>
 *   SUBJECT_DAILY_PERIOD_LIMIT : one NORMAL subject holds at most 2 periods a day,
 *                               and a 2-period day must be back-to-back.
 *                               1 hard point per surplus period on an over-full
 *                               day + 1 for a non-consecutive 2-period day.
 *   SECTION_DAILY_PAIR_LIMIT   : one section holds at most ONE back-to-back
 *                               NORMAL subject a day.
 *                               1 hard point per extra paired subject.
 * </pre>
 *
 * <p>Both exclude unassigned lessons and LAB components (a lab block is governed
 * by {@code LabConsecutiveBlockConstraint} and may share a day with a theory
 * pair), and both group per section so two sections meeting the same subject on
 * the same day are never merged.
 */
class SubjectDailyDistributionConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableFaculty F1 = faculty(1);
    private static final PlannableRoom HALL = room(1);
    private static final PlannableRoom LAB_ROOM = room(5, "LAB", 60);
    private static final PlannableSubject LAB_SUBJECT = subject(900L, "PHY-LAB", "LAB", 1L, 6);

    // ── SUBJECT_DAILY_PERIOD_LIMIT ─────────────────────────────────────────

    // 1. One period a day — the target pattern for a 5-hour subject — is clean.
    @Test
    void subjectDailyLimit_onePeriodPerDay_hasNoImpact() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDailyPeriodLimit)
            .given(
                theory(1, 1, 1, "MATH101", mon(1)),
                theory(2, 1, 1, "MATH101", tue(1)),
                theory(3, 1, 1, "MATH101", wed(1)),
                mon(1), tue(1), wed(1))
            .hasNoImpact();
    }

    // 2. A back-to-back pair is legal: the pair IS the pattern.
    @Test
    void subjectDailyLimit_backToBackPair_hasNoImpact() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDailyPeriodLimit)
            .given(
                theory(1, 1, 1, "MATH101", mon(1)),
                theory(2, 1, 1, "MATH101", mon(2)),
                mon(1), mon(2))
            .hasNoImpact();
    }

    // 3. A pair split by a break/gap is one hard point.
    @Test
    void subjectDailyLimit_nonConsecutivePair_isOneViolation() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDailyPeriodLimit)
            .given(
                theory(1, 1, 1, "MATH101", mon(1)),
                theory(2, 1, 1, "MATH101", mon(3)),
                mon(1), mon(3))
            .penalizesBy(1);
    }

    // 4. Three periods on one day is one surplus period; the run above it is not
    // scored again as a gap violation.
    @Test
    void subjectDailyLimit_threePeriodsInARow_isOneSurplus() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDailyPeriodLimit)
            .given(
                theory(1, 1, 1, "MATH101", mon(1)),
                theory(2, 1, 1, "MATH101", mon(2)),
                theory(3, 1, 1, "MATH101", mon(3)),
                mon(1), mon(2), mon(3))
            .penalizesBy(1);
    }

    // 5. Every period above the daily ceiling counts: 5 in a row is 3 surplus.
    @Test
    void subjectDailyLimit_surplusPeriods_accumulateAboveTheCeiling() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDailyPeriodLimit)
            .given(
                theory(1, 1, 1, "MATH101", mon(1)),
                theory(2, 1, 1, "MATH101", mon(2)),
                theory(3, 1, 1, "MATH101", mon(3)),
                theory(4, 1, 1, "MATH101", mon(4)),
                theory(5, 1, 1, "MATH101", mon(5)),
                mon(1), mon(2), mon(3), mon(4), mon(5))
            .penalizesBy(3);
    }

    // 6. The rule is per subject and per section: the same 2-period shape on a
    // different day, subject, or section is clean.
    @Test
    void subjectDailyLimit_isScopedPerSubjectDayAndSection() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDailyPeriodLimit)
            .given(
                theory(1, 1, 1, "MATH101", mon(1)),
                theory(2, 1, 1, "MATH101", mon(2)),
                theory(3, 1, 2, "PHY201", mon(1)),
                theory(4, 1, 2, "PHY201", mon(2)),
                theory(5, 2, 1, "MATH101", mon(1)),
                theory(6, 2, 1, "MATH101", mon(2)),
                mon(1), mon(2))
            .hasNoImpact();
    }

    // 7. A LAB block is exempt: the labConsecutiveBlock rule governs it, so its
    // 3-period run must not be scored as theory over-fill.
    @Test
    void subjectDailyLimit_labBlock_isExempt() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDailyPeriodLimit)
            .given(
                lab(1, 1, 101L, mon(1)),
                lab(2, 1, 101L, mon(2)),
                lab(3, 1, 102L, mon(4)),
                mon(1), mon(2), mon(4))
            .hasNoImpact();
    }

    // ── SECTION_DAILY_PAIR_LIMIT ───────────────────────────────────────────

    // 8. One paired subject + singles on the same day is the intended shape.
    @Test
    void pairLimit_onePairedSubject_hasNoImpact() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::sectionDailyPairLimit)
            .given(
                theory(1, 1, 1, "MATH101", mon(1)),
                theory(2, 1, 1, "MATH101", mon(2)),
                theory(3, 1, 2, "PHY201", mon(4)),
                mon(1), mon(2), mon(4))
            .hasNoImpact();
    }

    // 9. A second paired subject on the same section-day is one hard point, even
    // though the two pairs sit in disjoint slots.
    @Test
    void pairLimit_twoPairedSubjectsSameDay_isOneViolation() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::sectionDailyPairLimit)
            .given(
                theory(1, 1, 1, "MATH101", mon(1)),
                theory(2, 1, 1, "MATH101", mon(2)),
                theory(3, 1, 2, "PHY201", mon(4)),
                theory(4, 1, 2, "PHY201", mon(5)),
                mon(1), mon(2), mon(4), mon(5))
            .penalizesBy(1);
    }

    // 10. Three paired subjects on one day is 2 hard points.
    @Test
    void pairLimit_threePairedSubjects_accumulate() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::sectionDailyPairLimit)
            .given(
                theory(1, 1, 1, "MATH101", mon(1)),
                theory(2, 1, 1, "MATH101", mon(2)),
                theory(3, 1, 2, "PHY201", mon(4)),
                theory(4, 1, 2, "PHY201", mon(5)),
                theory(5, 1, 3, "CHE301", mon(7)),
                theory(6, 1, 3, "CHE301", mon(8)),
                mon(1), mon(2), mon(4), mon(5), mon(7), mon(8))
            .penalizesBy(2);
    }

    // 11. The same subject paired on two different days is the intended pattern,
    // never a violation.
    @Test
    void pairLimit_sameSubjectPairedOnTwoDays_hasNoImpact() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::sectionDailyPairLimit)
            .given(
                theory(1, 1, 1, "MATH101", mon(1)),
                theory(2, 1, 1, "MATH101", mon(2)),
                theory(3, 1, 1, "MATH101", tue(1)),
                theory(4, 1, 1, "MATH101", tue(2)),
                mon(1), mon(2), tue(1), tue(2))
            .hasNoImpact();
    }

    // 12. Pairing is per section-day: two sections may each pair a different
    // subject on the same day.
    @Test
    void pairLimit_isScopedPerSection() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::sectionDailyPairLimit)
            .given(
                theory(1, 1, 1, "MATH101", mon(1)),
                theory(2, 1, 1, "MATH101", mon(2)),
                theory(3, 2, 2, "PHY201", mon(1)),
                theory(4, 2, 2, "PHY201", mon(2)),
                mon(1), mon(2))
            .hasNoImpact();
    }

    // 13. A non-consecutive 2-period day is not a "pair": it is scored by
    // SUBJECT_DAILY_PERIOD_LIMIT, and must not also count as a pair owner here.
    @Test
    void pairLimit_splitDay_isNotCountedAsAPair() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::sectionDailyPairLimit)
            .given(
                theory(1, 1, 1, "MATH101", mon(1)),
                theory(2, 1, 1, "MATH101", mon(3)),
                theory(3, 1, 2, "PHY201", mon(4)),
                theory(4, 1, 2, "PHY201", mon(5)),
                mon(1), mon(3), mon(4), mon(5))
            .hasNoImpact();
    }

    // 14. A LAB block may share a day with a theory pair without becoming a
    // second pair owner.
    @Test
    void pairLimit_labBlock_mayShareAPairDay() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::sectionDailyPairLimit)
            .given(
                theory(1, 1, 1, "MATH101", mon(1)),
                theory(2, 1, 1, "MATH101", mon(2)),
                lab(3, 1, 101L, mon(4)),
                lab(4, 1, 101L, mon(5)),
                mon(1), mon(2), mon(4), mon(5))
            .hasNoImpact();
    }

    // 15. Two paired subjects AND two lab blocks: only the two theory pairs are
    // scored (1 hard point).
    @Test
    void pairLimit_ignoresLabBlocksWhenCounting() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::sectionDailyPairLimit)
            .given(
                theory(1, 1, 1, "MATH101", mon(1)),
                theory(2, 1, 1, "MATH101", mon(2)),
                theory(3, 1, 2, "PHY201", mon(4)),
                theory(4, 1, 2, "PHY201", mon(5)),
                lab(5, 1, 101L, mon(7)),
                lab(6, 1, 101L, mon(8)),
                mon(1), mon(2), mon(4), mon(5), mon(7), mon(8))
            .penalizesBy(1);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static PlannableTimeSlot mon(int order) {
        return window(order, "MON", order);
    }

    private static PlannableTimeSlot tue(int order) {
        return window(100 + order, "TUE", order);
    }

    private static PlannableTimeSlot wed(int order) {
        return window(200 + order, "WED", order);
    }

    /** NORMAL lesson of a distinct subject derived from its code, on its own faculty. */
    private static PlanningLesson theory(long id, long sectionId, long facultyId, String code,
            PlannableTimeSlot slot) {
        return lesson(id, sectionId, faculty(facultyId),
            subject(codeId(code), code, "THEORY", facultyId, 5), HALL, slot);
    }

    /** LAB lesson; the two periods of session {@code sessionId} form one block. */
    private static PlanningLesson lab(long id, long sectionId, long sessionId, PlannableTimeSlot slot) {
        PlanningLesson lesson = lesson(id, sectionId, F1, LAB_SUBJECT, LAB_ROOM, slot);
        lesson.setPracticalSessionId(sessionId);
        lesson.setPracticalSessionSize(2);
        return lesson;
    }

    private static long codeId(String code) {
        return Math.abs((long) code.hashCode());
    }
}
