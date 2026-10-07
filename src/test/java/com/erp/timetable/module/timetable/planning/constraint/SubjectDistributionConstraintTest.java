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
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.subject;
import static com.erp.timetable.module.timetable.planning.constraint.ConstraintTestFixtures.window;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6C — {@code TimetableConstraintProvider#subjectDistribution} (soft).
 *
 * <p>The objective steers repeated sessions of the same subject onto the
 * pair/single teaching-day pattern, matching the Greedy planner's
 * {@code idealDaySizes} business rule: a NORMAL subject holds at most 2 periods
 * a day, back-to-back when it holds 2. Exact scoring formula:
 * <pre>
 *   idealDays(subject)     = doubleDays + singleDays
 *                            doubleDays = max(0, weeklyHours - 5)
 *                            singleDays = weeklyHours - 2 * doubleDays
 *   daysUsed(subject)      = distinct teaching days
 *   dayPenalty             = |daysUsed - idealDays| * EXTRA_THEORY_DAY_WEIGHT
 *   gapPenalty             = non-adjacent pairs of same-day THEORY sessions
 *   penalty(subject)       = dayPenalty + gapPenalty
 *   total                  = SUM over (subject, section)
 * </pre>
 * The day penalty is TWO-SIDED: with a 2-period daily ceiling a packed day is
 * now wrong in both directions, so a 5-hour subject scores 0 on 5 separate days
 * ({@code 1+1+1+1+1}) and is penalised for both spreading past the ideal and
 * compacting below it. {@code EXTRA_THEORY_DAY_WEIGHT} (5, the daily cap)
 * dominates the gap weight so "matching the teaching-day pattern" always beats
 * same-day compactness.
 *
 * <p>The ideal is computed from the lesson count of the group, so a lesson with
 * no window (unassigned) does not raise it.
 *
 * <p>LAB is exempt from clustering: it keeps the original spread rule (one point
 * per same-day session beyond the first, a session being a consecutive run), so
 * the sessions of a {@code sessionBlockSize} 1 lab stay distinct single-period
 * runs and never merge into one oversized block. The hierarchy stays hard rules
 * &gt; UNASSIGNED_LESSONS &gt;
 * IDLE_GAP &gt; SUBJECT_DISTRIBUTION; this constraint never contributes to the
 * hard score, never penalises different subjects on one day, and never blocks a
 * placement the hard rules allow. The 2-period daily rule itself is enforced HARD
 * by {@code TimetableConstraintProvider#subjectDailyPeriodLimit} and the one
 * back-to-back subject per day by {@code #sectionDailyPairLimit}.
 */
class SubjectDistributionConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableFaculty PROF = faculty(1);
    // 5 weekly hours → 1+1+1+1+1 → ideal 5 teaching days.
    private static final PlannableSubject MATH = subject(1, "MATH101", "THEORY", 1L, 5);
    private static final PlannableSubject PHYSICS = subject(2, "PHY201", "THEORY", 1L, 5);
    // 2 weekly hours → ideal 2 teaching days (used to isolate the gap term).
    private static final PlannableSubject SHORT = subject(4, "SHORT101", "THEORY", 1L, 2);
    // 6 weekly hours → ideal 2 teaching days, but LAB is exempt from clustering
    // (keeps the spread rule), so the ideal never affects these LAB fixtures.
    private static final PlannableSubject PHYSICS_LAB = subject(3, "PHY201L", "LAB", 1L, 6);

    // 1. One session → 0 distribution penalty.
    @Test
    void singleSession_hasNoImpact() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, MATH, mon(2)),
                mon(2))
            .hasNoImpact();
    }

    // 2. The whole week compacted onto one day is now WRONG: a 5-hour subject
    // belongs on 5 separate days, so 1 day is 4 days short of the ideal.
    @Test
    void allSessionsOnOneDay_consecutive_isPenalisedAsCompaction() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, MATH, mon(1)),
                teachingLesson(2, 1, MATH, mon(2)),
                teachingLesson(3, 1, MATH, mon(3)),
                teachingLesson(4, 1, MATH, mon(4)),
                teachingLesson(5, 1, MATH, mon(5)),
                mon(1), mon(2), mon(3), mon(4), mon(5))
            .penalizesBy(4 * TimetableConstraintProvider.EXTRA_THEORY_DAY_WEIGHT);
    }

    // 3. The deviation is TWO-SIDED: the ideal day count scores 0, and both
    // spreading past it and compacting below it cost EXTRA_THEORY_DAY_WEIGHT per
    // day of deviation.
    @Test
    void teachingDays_deviateInBothDirectionsFromTheIdeal() {
        // The intended pattern for a 5-hour subject: one period per day.
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, MATH, mon(1)),
                teachingLesson(2, 1, MATH, tue(1)),
                teachingLesson(3, 1, MATH, wed(1)),
                teachingLesson(4, 1, MATH, thu(1)),
                teachingLesson(5, 1, MATH, fri(1)),
                mon(1), tue(1), wed(1), thu(1), fri(1))
            .hasNoImpact();

        // Six days for a 6-lesson subject → ideal 5 days (2+1+1+1+1) → 1 extra day × 5.
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, MATH, mon(1)),
                teachingLesson(2, 1, MATH, tue(1)),
                teachingLesson(3, 1, MATH, wed(1)),
                teachingLesson(4, 1, MATH, thu(1)),
                teachingLesson(5, 1, MATH, fri(1)),
                teachingLesson(6, 1, MATH, sat(1)),
                mon(1), tue(1), wed(1), thu(1), fri(1), sat(1))
            .penalizesBy(TimetableConstraintProvider.EXTRA_THEORY_DAY_WEIGHT);

        // Three lessons compacted onto two days → ideal 3 days → 1 short × 5.
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, MATH, mon(1)),
                teachingLesson(2, 1, MATH, mon(2)),
                teachingLesson(3, 1, MATH, tue(1)),
                mon(1), mon(2), tue(1))
            .penalizesBy(TimetableConstraintProvider.EXTRA_THEORY_DAY_WEIGHT);

        // Four lessons on two days → ideal 4 days → 2 short × 5.
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, MATH, mon(1)),
                teachingLesson(2, 1, MATH, mon(2)),
                teachingLesson(3, 1, MATH, mon(3)),
                teachingLesson(4, 1, MATH, tue(1)),
                mon(1), mon(2), mon(3), tue(1))
            .penalizesBy(2 * TimetableConstraintProvider.EXTRA_THEORY_DAY_WEIGHT);
    }

    // 4. Same-day sessions that are NOT back-to-back cost one point per gap, on
    // top of the day term. A 2-hour subject has an ideal of 2 days, so both
    // fixtures below sit 1 day short and only the gap term differs (1 vs 0).
    @Test
    void sameDaySplitSessions_penalizePerGap() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, SHORT, mon(1)),
                teachingLesson(2, 1, SHORT, mon(3)),
                mon(1), mon(3))
            .penalizesBy(TimetableConstraintProvider.EXTRA_THEORY_DAY_WEIGHT + 1);

        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, SHORT, mon(1)),
                teachingLesson(2, 1, SHORT, mon(2)),
                mon(1), mon(2))
            .penalizesBy(TimetableConstraintProvider.EXTRA_THEORY_DAY_WEIGHT);
    }

    // 5. On the same day count, back-to-back sessions outscore split sessions.
    @Test
    void backToBack_outScoresSplitSameDay() {
        HardSoftScore backToBack = constraintVerifier
            .verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, SHORT, mon(1)),
                teachingLesson(2, 1, SHORT, mon(2)),
                mon(1), mon(2))
            .getScore();

        HardSoftScore split = constraintVerifier
            .verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, SHORT, mon(1)),
                teachingLesson(2, 1, SHORT, mon(3)),
                mon(1), mon(3))
            .getScore();

        assertEquals(
            HardSoftScore.of(0, -TimetableConstraintProvider.EXTRA_THEORY_DAY_WEIGHT), backToBack);
        assertEquals(
            HardSoftScore.of(0, -TimetableConstraintProvider.EXTRA_THEORY_DAY_WEIGHT - 1), split);
        assertTrue(backToBack.compareTo(split) > 0,
            "back-to-back sessions on one day must outscore the same sessions split apart");
    }

    // 6. Different subjects on the same day → no impact.
    @Test
    void differentSubjectsSameDay_noImpact() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, MATH, mon(1)),
                teachingLesson(2, 1, PHYSICS, mon(2)),
                mon(1), mon(2))
            .hasNoImpact();
    }

    // 7. LAB keeps the original spread rule: two complete blocks on one day are
    // a two-session day (one point); one block on each of two days is free. Labs
    // are never rewarded for clustering, so size-1 sessions stay separate runs.
    @Test
    void labBlocks_keepOriginalSpreadSemantics() {
        // Two complete 3-period lab blocks on the SAME day → 2 sessions on one
        // day → 1 point.
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                labLesson(1, 1, mon(1)),
                labLesson(2, 1, mon(2)),
                labLesson(3, 1, mon(3)),
                labLesson(4, 1, mon(5)),
                labLesson(5, 1, mon(6)),
                labLesson(6, 1, mon(7)),
                mon(1), mon(2), mon(3), mon(5), mon(6), mon(7))
            .penalizesBy(1);

        // The same 6 hours split across two days (one block per day) → clean
        // spread → 0.
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                labLesson(1, 1, mon(1)),
                labLesson(2, 1, mon(2)),
                labLesson(3, 1, mon(3)),
                labLesson(4, 1, tue(1)),
                labLesson(5, 1, tue(2)),
                labLesson(6, 1, tue(3)),
                mon(1), mon(2), mon(3), tue(1), tue(2), tue(3))
            .hasNoImpact();
    }

    // 8. An unassigned lesson adds no penalty and does not raise the ideal
    // either: the ideal follows the number of lessons actually in the group.
    @Test
    void unassignedLesson_doesNotAddPenalty() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, MATH, mon(1)),
                teachingLesson(2, 1, MATH, tue(1)),
                unassignedLesson(3, 1, MATH),
                mon(1), tue(1))
            .hasNoImpact();
    }

    // 9. The soft day penalty never touches the hard score.
    @Test
    void hardScore_remainsUnaffected() {
        // Three lessons on two days: ideal 3 days → 1 soft day penalty, 0 hard.
        HardSoftScore compacted = constraintVerifier
            .verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, MATH, mon(1)),
                teachingLesson(2, 1, MATH, mon(2)),
                teachingLesson(3, 1, MATH, tue(1)),
                mon(1), mon(2), tue(1))
            .getScore();

        assertEquals(0, compacted.hardScore(),
            "the distribution objective must never contribute a hard penalty");
        assertEquals(
            HardSoftScore.of(0, -TimetableConstraintProvider.EXTRA_THEORY_DAY_WEIGHT), compacted);
    }

    // 10. Explicit score-direction comparison: matching the pair/single pattern
    // outscores compacting the same sessions onto one day, because a 2-period
    // daily ceiling makes compaction wrong, not merely suboptimal.
    @Test
    void scoreDirection_pairSinglePatternOutscoresCompaction() {
        // Schedule A: one MATH lesson on each of its 4 ideal days → 0.
        HardSoftScore scattered = constraintVerifier
            .verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, MATH, mon(1)),
                teachingLesson(2, 1, MATH, tue(1)),
                teachingLesson(3, 1, MATH, wed(1)),
                teachingLesson(4, 1, MATH, thu(1)),
                mon(1), tue(1), wed(1), thu(1))
            .getScore();

        // Schedule B: the same four lessons compacted back-to-back on one day →
        // 3 days short of the ideal × 5.
        HardSoftScore compacted = constraintVerifier
            .verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, MATH, mon(1)),
                teachingLesson(2, 1, MATH, mon(2)),
                teachingLesson(3, 1, MATH, mon(3)),
                teachingLesson(4, 1, MATH, mon(4)),
                mon(1), mon(2), mon(3), mon(4))
            .getScore();

        assertEquals(HardSoftScore.of(0, 0), scattered);
        assertEquals(
            HardSoftScore.of(0, -3 * TimetableConstraintProvider.EXTRA_THEORY_DAY_WEIGHT), compacted);
        assertTrue(scattered.compareTo(compacted) > 0,
            "the spread pattern must outscore the same lessons compacted onto one day");
    }

    // 11. A subject explicitly configured 2×CONSECUTIVE with 2 weekly hours
    // (2 periods/week) is scored against an ideal of ONE day, so a single
    // back-to-back pair is the clean solution and spreading it is penalised.
    @Test
    void explicitConsecutiveBlock_twoHourSubject_scoresAsOneDay() {
        // The configured block: both periods back-to-back on one day → ideal → 0.
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                pairLesson(1, 1, mon(1)),
                pairLesson(2, 1, mon(2)),
                mon(1), mon(2))
            .hasNoImpact();

        // The same 2 periods split across two days contradict the request.
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                pairLesson(1, 1, mon(1)),
                pairLesson(2, 1, tue(1)),
                mon(1), tue(1))
            .penalizesBy(TimetableConstraintProvider.EXTRA_THEORY_DAY_WEIGHT);
    }

    // 12. The same 2 lessons WITHOUT the explicit block (default SINGLE) keep the
    // spread ideal of 2 days — the block must be earned by configuration, not
    // granted to every 2-hour subject.
    @Test
    void singleConfigured_twoHourSubject_scoresAsTwoDays() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::subjectDistribution)
            .given(
                teachingLesson(1, 1, SHORT, mon(1)),
                teachingLesson(2, 1, SHORT, tue(1)),
                mon(1), tue(1))
            .hasNoImpact();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    // 2 weekly hours configured as an explicit 2×CONSECUTIVE block
    // (sessionBlockSize 2).
    private static final PlannableSubject PAIR = subject(5, "PT101", "THEORY", 1L, 2, 2);

    private static PlanningLesson pairLesson(long id, long sectionId, PlannableTimeSlot slot) {
        return ConstraintTestFixtures.lesson(id, sectionId, PROF, PAIR, room(1), slot);
    }

    private static PlannableTimeSlot mon(int order) {
        return window(order, "MON", order);
    }

    private static PlannableTimeSlot tue(int order) {
        return window(100 + order, "TUE", order);
    }

    private static PlannableTimeSlot wed(int order) {
        return window(200 + order, "WED", order);
    }

    private static PlannableTimeSlot thu(int order) {
        return window(300 + order, "THU", order);
    }

    private static PlannableTimeSlot fri(int order) {
        return window(400 + order, "FRI", order);
    }

    private static PlannableTimeSlot sat(int order) {
        return window(500 + order, "SAT", order);
    }

    private static PlanningLesson teachingLesson(long id, long sectionId, PlannableSubject subject,
            PlannableTimeSlot timeSlot) {
        return ConstraintTestFixtures.lesson(id, sectionId, PROF, subject, room(1), timeSlot);
    }

    private static PlanningLesson labLesson(long id, long sectionId, PlannableTimeSlot timeSlot) {
        return ConstraintTestFixtures.lesson(id, sectionId, PROF, PHYSICS_LAB, room(5, "LAB", 60), timeSlot);
    }

    private static PlanningLesson unassignedLesson(long id, long sectionId, PlannableSubject subject) {
        return ConstraintTestFixtures.lesson(id, sectionId, PROF, subject, null, null);
    }
}
