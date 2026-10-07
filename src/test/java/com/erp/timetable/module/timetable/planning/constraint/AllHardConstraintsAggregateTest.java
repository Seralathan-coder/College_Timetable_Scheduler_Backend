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
import static com.erp.timetable.module.timetable.planning.constraint.TimetableConstraintProvider.UNASSIGNED_LESSON_WEIGHT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Aggregate scoring across all hard constraints (Phase 3A + Phase 3B).
 *
 * <p>{@code verifyThat()} runs the full {@link TimetableConstraintProvider}, so
 * the expected {@link HardSoftScore} must account for every constraint firing
 * in the fixture. The mixed scenario below uses disjoint faculty/room/section
 * instances per violation so the penalties add up cleanly.
 */
class AllHardConstraintsAggregateTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    @Test
    void cleanTimetable_isFeasibleZero() {
        PlannableFaculty professor = faculty(20, 6, 24);
        PlannableRoom classroom = room(20, "LECTURE_HALL", 60);

        // MON slots 1,2,5 (valid consecutive pattern) + a TUE lesson in another
        // section. One subject per lesson, so the soft subject-distribution
        // objective (Phase 6C) stays silent and the total remains a clean zero.
        HardSoftScore score = constraintVerifier.verifyThat()
            .given(
                lesson(101, 20, professor, subject(20, "PHY101", "THEORY", 20L), classroom, window(101, "MON", 1)),
                lesson(102, 20, professor, subject(21, "PHY102", "THEORY", 20L), classroom, window(102, "MON", 2)),
                lesson(103, 20, professor, subject(22, "PHY103", "THEORY", 20L), classroom, window(103, "MON", 5)),
                lesson(104, 21, professor, subject(23, "PHY104", "THEORY", 20L), classroom, window(104, "TUE", 1)))
            .getScore();

        assertEquals(HardSoftScore.ZERO, score);
    }

    @Test
    void unassignedLessons_contributeOnlyTheSoftUnassignedPenalty() {
        PlannableFaculty onLeave = faculty(30);
        onLeave.setStatus("LEAVE");
        PlannableSubject subject = subject(30, "PHY101", "THEORY", 30L);

        // Unassigned lessons never violate a hard constraint (LEAVE faculty
        // would, if scheduled); they only cost UNASSIGNED_LESSON_WEIGHT soft
        // points each via the "Minimize unassigned lessons" constraint.
        HardSoftScore score = constraintVerifier.verifyThat()
            .given(
                lesson(301, 30, onLeave, subject, null, null),
                lesson(302, 30, onLeave, subject, null, null),
                availabilityFact(30, "MON", 1, "BLOCKED"))
            .getScore();

        assertEquals(HardSoftScore.of(0, -2 * UNASSIGNED_LESSON_WEIGHT), score);
    }

    /**
     * One violation per rule (fixtures are pairwise disjoint), expected total
     * hard penalty 13:
     *
     * <pre>
     *   S1  section + faculty + room clash (2 lessons, same window)     = 3
     *   S2  theory subject in a LAB room                                 = 1
     *   S3  room capacity 20 &lt; required 40                              = 1
     *   S4  faculty dept 4, target dept 9                                 = 1
     *   S5  subject assigned to faculty 6, taught by faculty 5            = 1
     *   S6  faculty with 7 periods in one day (college cap 5)             = 2
     *   S7  faculty with 6 weekly periods (cap 5)                         = 1
     *   S8  blocked availability fact matches the lesson's slot           = 1
     *   S9  faculty on LEAVE with a scheduled lesson                      = 1
     *   S10 broken lab block (a single session split across days)            = 1
     *   ────────────────────────────────────────────────────────────────
     *   TOTAL                                                          = 13
     * </pre>
     *
     * <p>The old consecutive-teaching rule (S11) no longer fires: back-to-back
     * teaching is now allowed by college policy.
     */
    @Test
    void mixedViolations_accumulateAcrossAllConstraints() {
        // S1: triple clash — same section, faculty, room, window. Distinct
        // subjects keep the soft subject-distribution objective (Phase 6C) out
        // of the hard-only aggregate.
        PlannableFaculty f1 = faculty(1);
        PlannableRoom r1 = room(1, "LECTURE_HALL", 60);
        PlannableTimeSlot clashWindow = window(1, "MON", 1);
        PlanningLesson l1 = lesson(1, 1, f1, subject(1, "PHY101", "THEORY", 1L), r1, clashWindow);
        PlanningLesson l2 = lesson(2, 1, f1, subject(100, "PHY102", "THEORY", 1L), r1, clashWindow);

        // S2: theory subject in a lab room.
        PlanningLesson l3 = lesson(3, 2, faculty(2), subject(2, "PHY102", "THEORY", 2L),
            room(2, "LAB", 60), window(3, "TUE", 1));

        // S3: capacity violation (20 < 40).
        PlanningLesson l4 = lesson(4, 3, faculty(3), subject(3, "MAT201", "THEORY", 3L),
            room(3, "LECTURE_HALL", 20), window(4, "WED", 1));

        // S4: department permission violation (target dept 9). The subject has
        // NO assigned faculty, so the new assigned-faculty exemption cannot fire
        // and the pure department-mismatch rule must penalise.
        PlanningLesson l5 = lesson(5, 4, faculty(4), subject(4, "CS301", "THEORY", null),
            9L, 40, room(4, "LECTURE_HALL", 60), window(5, "THU", 1));

        // S5: assigned-subject violation (faculty 5 teaches a subject assigned to faculty 6).
        PlanningLesson l6 = lesson(6, 5, faculty(5), subject(5, "PHY103", "THEORY", 6L),
            room(5, "LECTURE_HALL", 60), window(6, "FRI", 1));

        // S6: daily-hours violation — 7 periods for one faculty on MON (own cap 6,
        // but the college-wide ceiling is now 5, so 7 - 5 = 2 hard points).
        // Distinct subjects per lesson so only the daily-hours hard rule fires.
        PlannableFaculty f6 = faculty(7, 6, 24);
        int[] dailySlots = {1, 2, 3, 4, 5, 6, 7};
        PlanningLesson[] daily = new PlanningLesson[dailySlots.length];
        for (int i = 0; i < dailySlots.length; i++) {
            daily[i] = lesson(7 + i, 6, f6, subject(60 + i, "CH201-" + i, "THEORY", 7L),
                room(11 + i, "LECTURE_HALL", 60), window(7 + i, "MON", dailySlots[i]));
        }

        // S7: weekly-hours violation — 6 periods for one faculty (cap 5).
        PlannableFaculty f7 = faculty(8, 6, 5);
        // 6 weekly periods of NORMAL subject → the pair/single objective (Phase
        // 6C) wants this subject on 5 teaching days (2+1+1+1+1); the fixture
        // deliberately spreads one period across all six days to isolate the
        // weekly-hours hard rule, which adds a soft 1-extra-day ×
        // EXTRA_THEORY_DAY_WEIGHT penalty on top (see the expected total below).
        // One period per day keeps it clear of the new daily 2-period and
        // one-back-to-back-subject-per-day hard rules.
        PlannableSubject s7 = subject(7, "EN201", "THEORY", 8L);
        String[] days = {"MON", "TUE", "WED", "THU", "FRI", "SAT"};
        PlanningLesson[] weekly = new PlanningLesson[6];
        for (int i = 0; i < 6; i++) {
            weekly[i] = lesson(12 + i, 7, f7, s7,
                room(21 + i, "LECTURE_HALL", 60), window(12 + i, days[i], 1));
        }

        // S8: blocked availability fact on the lesson's slot.
        PlanningLesson l18 = lesson(18, 8, faculty(9), subject(8, "PHY104", "THEORY", 9L),
            room(31, "LECTURE_HALL", 60), window(18, "MON", 3));

        // S9: faculty on LEAVE with a scheduled lesson.
        PlannableFaculty f9 = faculty(10);
        f9.setStatus("LEAVE");
        PlanningLesson l19 = lesson(19, 9, f9, subject(9, "CS101", "THEORY", 10L),
            room(32, "LECTURE_HALL", 60), window(19, "TUE", 2));

        // S10: broken lab block — a single 2-period session split across two
        // days (each day-group holds 1 period instead of a complete block of 2).
        // The two lab lessons sit on distinct days (one session per day), which
        // the LAB spread rule (Phase 6C) leaves unpenalised — see the expected
        // soft total below.
        PlannableFaculty f10 = faculty(11);
        PlannableSubject s10 = subject(10, "PHY-LAB", "LAB", 11L);
        PlanningLesson l20 = lesson(20, 10, f10, s10, room(33, "LAB", 60), window(20, "MON", 1));
        l20.setPracticalSessionId(100L);
        l20.setPracticalSessionSize(2);
        PlanningLesson l21 = lesson(21, 10, f10, s10, room(33, "LAB", 60), window(21, "TUE", 1));
        l21.setPracticalSessionId(100L);
        l21.setPracticalSessionSize(2);

        Object[] given = new Object[6 + daily.length + weekly.length + 5];
        given[0] = l1;
        given[1] = l2;
        given[2] = l3;
        given[3] = l4;
        given[4] = l5;
        given[5] = l6;
        System.arraycopy(daily, 0, given, 6, daily.length);
        System.arraycopy(weekly, 0, given, 6 + daily.length, weekly.length);
        given[6 + daily.length + weekly.length] = l18;
        given[6 + daily.length + weekly.length + 1] = l19;
        given[6 + daily.length + weekly.length + 2] = l20;
        given[6 + daily.length + weekly.length + 3] = l21;
        given[given.length - 1] = availabilityFact(9, "MON", 18, "BLOCKED");

        HardSoftScore score = constraintVerifier.verifyThat()
            .given(given)
            .getScore();

        // -13 hard (all hard violations above) and -5 soft: EN201 (S7) is
        // taught across 6 days, one period per day. Its 6 NORMAL lessons need
        // 5 teaching days (2+1+1+1+1) under the Phase 6C pair/single objective,
        // so the sixth day costs 1 day × EXTRA_THEORY_DAY_WEIGHT (5). The S10
        // lab spread across 2 days is exempt (LAB keeps the spread rule), so it
        // adds no soft penalty. Neither new hard rule fires on this fixture: the
        // S6 daily overload is 7 DISTINCT subjects on MON (no back-to-back pair)
        // and EN201 never puts two periods on one day.
        assertEquals(HardSoftScore.of(-13,
            -TimetableConstraintProvider.EXTRA_THEORY_DAY_WEIGHT), score);
    }
}
