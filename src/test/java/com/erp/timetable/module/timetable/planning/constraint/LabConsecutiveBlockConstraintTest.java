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
 * Tests for {@code TimetableConstraintProvider#labConsecutiveBlock} — the
 * Timefold translation of the Greedy {@code LabConsecutiveBlockConstraint}
 * with Greedy-exact <em>all-or-nothing</em> session semantics.
 *
 * <p>Practical lessons are grouped into explicit sessions by
 * {@link PlanningLesson#getPracticalSessionId} (built by the {@code timefold}
 * engine from the subject's per-subject practical block size), each with an
 * expected size {@link PlanningLesson#getPracticalSessionSize}. A session is
 * valid only when placed in FULL — every period assigned, on one day, in
 * strictly consecutive non-break slots. A partial session is a hard violation,
 * so the solver completes the block or leaves the whole session unassigned,
 * identical to the Greedy engine which never places a partial practical block.
 * A session with every period unassigned is valid (the "cannot be placed"
 * state, penalised only softly).
 *
 * <p><b>forEach semantics.</b> {@code forEach(PlanningLesson.class)} skips
 * lessons whose planning variables are {@code null} (unassigned lessons are
 * invisible), so a partial session surfaces as an assigned group whose size
 * falls short of {@code practicalSessionSize} — exactly how the real solve
 * detects it. A fully unassigned session produces no group at all.
 */
class LabConsecutiveBlockConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableRoom ROOM = room(1, "LAB", 60);
    private static final PlannableSubject LAB = subject(1, "PHY-LAB", "LAB", 1L);
    private static final PlannableSubject THEORY = subject(2, "PHY101", "THEORY", 2L);

    /** LAB subject with an explicit per-subject block size of 2 (sessionBlockSize clamped to max 2). */
    private static final PlannableSubject LAB_BLOCK2 = labBlock2();

    private static PlannableSubject labBlock2() {
        PlannableSubject subject = subject(3, "PHY-LAB2", "LAB", 3L);
        subject.setSessionBlockSize(2);
        return subject;
    }

    private static PlanningLesson sessionLabLesson(long lessonId, PlannableFaculty professor, long sectionId,
            PlannableSubject subject, String day, int slotOrder, long sessionId, int sessionSize) {
        PlanningLesson built = lesson(lessonId, sectionId, professor, subject, ROOM, window(lessonId, day, slotOrder));
        built.setPracticalSessionId(sessionId);
        built.setPracticalSessionSize(sessionSize);
        return built;
    }

    private static PlanningLesson unassignedSessionLesson(long lessonId, PlannableFaculty professor, long sectionId,
            PlannableSubject subject, long sessionId, int sessionSize) {
        PlanningLesson built = lesson(lessonId, sectionId, professor, subject, null, null);
        built.setPracticalSessionId(sessionId);
        built.setPracticalSessionSize(sessionSize);
        return built;
    }

    @Test
    void fullTwoPeriodBlock_isNotPenalized() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, LAB, "MON", 1, 100L, 2),
                sessionLabLesson(2, professor, 1, LAB, "MON", 2, 100L, 2))
            .hasNoImpact();
    }

    @Test
    void threeConsecutiveSlots_inTwoPeriodSession_isPenalized() {
        // A 3-run cannot belong to a 2-period session: too many periods.
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, LAB, "MON", 1, 100L, 2),
                sessionLabLesson(2, professor, 1, LAB, "MON", 2, 100L, 2),
                sessionLabLesson(3, professor, 1, LAB, "MON", 3, 100L, 2))
            .penalizesBy(1);
    }

    @Test
    void singlePeriodOfTwoPeriodSession_partialBlock_isPenalized() {
        // A partially placed session (1 of 2 periods) is a hard violation.
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(sessionLabLesson(1, professor, 1, LAB, "MON", 1, 100L, 2))
            .penalizesBy(1);
    }

    @Test
    void twoNonConsecutiveLessons_isPenalized() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, LAB, "MON", 1, 100L, 2),
                sessionLabLesson(2, professor, 1, LAB, "MON", 3, 100L, 2))
            .penalizesBy(1);
    }

    @Test
    void fourPeriodSession_isPenalized() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, LAB, "MON", 1, 100L, 2),
                sessionLabLesson(2, professor, 1, LAB, "MON", 2, 100L, 2),
                sessionLabLesson(3, professor, 1, LAB, "MON", 3, 100L, 2),
                sessionLabLesson(4, professor, 1, LAB, "MON", 4, 100L, 2))
            .penalizesBy(1);
    }

    @Test
    void nonConsecutiveSlots_inThreePeriodSession_isPenalized() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, LAB, "MON", 1, 100L, 3),
                sessionLabLesson(2, professor, 1, LAB, "MON", 2, 100L, 3),
                sessionLabLesson(3, professor, 1, LAB, "MON", 4, 100L, 3))
            .penalizesBy(1);
    }

    @Test
    void outOfOrderLessons_stillValid() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, LAB, "MON", 2, 100L, 2),
                sessionLabLesson(2, professor, 1, LAB, "MON", 1, 100L, 2))
            .hasNoImpact();
    }

    @Test
    void lessonsSplitAcrossDays_isPenalized() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, LAB, "MON", 1, 100L, 2),
                sessionLabLesson(2, professor, 1, LAB, "TUE", 1, 100L, 2))
            .penalizesBy(1);
    }

    @Test
    void sessionWithAllPeriodsUnassigned_isValid() {
        // The whole session "could not be placed" — soft unassigned penalty only.
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                unassignedSessionLesson(1, professor, 1, LAB, 100L, 2),
                unassignedSessionLesson(2, professor, 1, LAB, 100L, 2))
            .hasNoImpact();
    }

    @Test
    void partiallyAssignedSession_withUnassignedPeriod_isPenalized() {
        // A 3-period session with only 2 periods placed: the unassigned period is
        // invisible to forEach (null planning variables), so the group holds 2
        // lessons against an expected size of 3 — a partial placement, hard.
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, LAB, "MON", 1, 100L, 3),
                sessionLabLesson(2, professor, 1, LAB, "MON", 2, 100L, 3),
                unassignedSessionLesson(3, professor, 1, LAB, 100L, 3))
            .penalizesBy(1);
    }

    @Test
    void theorySubject_isIgnored() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                lesson(1, 1, faculty(1), THEORY, room(2), window(1, "MON", 1)),
                lesson(2, 1, faculty(1), THEORY, room(2), window(2, "MON", 2)),
                lesson(3, 1, faculty(1), THEORY, room(2), window(3, "MON", 3)))
            .hasNoImpact();
    }

    @Test
    void blocksInDifferentSections_areSeparateSessions() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, LAB, "MON", 1, 100L, 2),
                sessionLabLesson(2, professor, 1, LAB, "MON", 2, 100L, 2),
                sessionLabLesson(4, professor, 2, LAB, "MON", 1, 200L, 2),
                sessionLabLesson(5, professor, 2, LAB, "MON", 2, 200L, 2))
            .hasNoImpact();
    }

    @Test
    void twoValidBlocksOnDifferentDays_isNotPenalized() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, LAB, "MON", 1, 100L, 2),
                sessionLabLesson(2, professor, 1, LAB, "MON", 2, 100L, 2),
                sessionLabLesson(4, professor, 1, LAB, "TUE", 1, 200L, 2),
                sessionLabLesson(5, professor, 1, LAB, "TUE", 2, 200L, 2))
            .hasNoImpact();
    }

    @Test
    void labWithSessionBlockSizeTwo_twoConsecutivePeriods_isNotPenalized() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, LAB_BLOCK2, "MON", 1, 100L, 2),
                sessionLabLesson(2, professor, 1, LAB_BLOCK2, "MON", 2, 100L, 2))
            .hasNoImpact();
    }

    @Test
    void labWithSessionBlockSizeTwo_threeConsecutivePeriods_isPenalized() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, LAB_BLOCK2, "MON", 1, 100L, 2),
                sessionLabLesson(2, professor, 1, LAB_BLOCK2, "MON", 2, 100L, 2),
                sessionLabLesson(3, professor, 1, LAB_BLOCK2, "MON", 3, 100L, 2))
            .penalizesBy(1);
    }

    @Test
    void labWithSessionBlockSizeTwo_nonConsecutiveTwoSlots_isPenalized() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, LAB_BLOCK2, "MON", 1, 100L, 2),
                sessionLabLesson(2, professor, 1, LAB_BLOCK2, "MON", 4, 100L, 2))
            .penalizesBy(1);
    }

    @Test
    void labWithSessionBlockSizeTwo_splitAcrossDays_isPenalized() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, LAB_BLOCK2, "MON", 1, 100L, 2),
                sessionLabLesson(2, professor, 1, LAB_BLOCK2, "TUE", 1, 100L, 2))
            .penalizesBy(1);
    }

    @Test
    void labWithExplicitSessionBlockSizeTwo_threePeriodSession_isPenalized() {
        // An explicit per-subject block size of 2 must NOT silently grow to 3:
        // a 3-run violates the configured block exactly like the global default.
        PlannableFaculty professor = faculty(1);
        PlannableSubject labBlock2 = subject(4, "PHY-LAB2", "LAB", 4L);
        labBlock2.setSessionBlockSize(2);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                sessionLabLesson(1, professor, 1, labBlock2, "MON", 1, 100L, 2),
                sessionLabLesson(2, professor, 1, labBlock2, "MON", 2, 100L, 2),
                sessionLabLesson(3, professor, 1, labBlock2, "MON", 3, 100L, 2))
            .penalizesBy(1);
    }

    @Test
    void unscheduledLabSession_allUnassigned_isIgnored() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::labConsecutiveBlock)
            .given(
                unassignedSessionLesson(1, professor, 1, LAB, 100L, 2),
                unassignedSessionLesson(2, professor, 1, LAB, 100L, 2))
            .hasNoImpact();
    }
}
