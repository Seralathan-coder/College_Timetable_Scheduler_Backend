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

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the Phase 3A hard constraints.
 *
 * <p>Each constraint is verified in isolation through Timefold's
 * {@link ConstraintVerifier}, and combined scenarios assert the total
 * {@link HardSoftScore} over all three constraints, which doubles as the score
 * calculation check for Phase 3A.
 */
class TimetableConstraintProviderTest {

    private static final long SECTION_A = 1L;
    private static final long SECTION_B = 2L;

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    // ================================ fixtures ================================

    private static PlannableFaculty faculty(long facultyId) {
        return PlannableFaculty.builder().facultyId(facultyId).employeeId("EMP-" + facultyId).build();
    }

    private static PlannableRoom room(long roomId) {
        return PlannableRoom.builder().roomId(roomId).roomNumber("R-" + roomId).build();
    }

    private static PlannableTimeSlot window(long slotId, String dayOfWeek) {
        return PlannableTimeSlot.builder()
            .timeSlotId(slotId)
            .dayOfWeek(dayOfWeek)
            .slotOrder(1)
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(10, 0))
            .isBreak(false)
            .build();
    }

    private static PlanningLesson lesson(long lessonId, long sectionId, PlannableFaculty faculty,
                                         PlannableRoom room, PlannableTimeSlot timeSlot) {
        return PlanningLesson.builder()
            .id(lessonId)
            .sectionId(sectionId)
            .subject(PlannableSubject.builder().subjectId(lessonId).subjectCode("SUB-" + lessonId).build())
            .faculty(faculty)
            .room(room)
            .timeSlot(timeSlot)
            .build();
    }

    // ============================ section conflict ============================

    @Test
    void sectionConflict_sameSectionSameWindow_isPenalized() {
        PlannableTimeSlot window = window(1, "MON");
        constraintVerifier.verifyThat(TimetableConstraintProvider::sectionConflict)
            .given(
                lesson(1, SECTION_A, faculty(1), room(1), window),
                lesson(2, SECTION_A, faculty(2), room(2), window))
            .penalizesBy(1);
    }

    @Test
    void sectionConflict_sameSectionDifferentWindows_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::sectionConflict)
            .given(
                lesson(1, SECTION_A, faculty(1), room(1), window(1, "MON")),
                lesson(2, SECTION_A, faculty(2), room(2), window(2, "TUE")))
            .hasNoImpact();
    }

    @Test
    void sectionConflict_threeLessonsSameWindow_penalizesByTwo() {
        PlannableTimeSlot window = window(1, "MON");
        constraintVerifier.verifyThat(TimetableConstraintProvider::sectionConflict)
            .given(
                lesson(1, SECTION_A, faculty(1), room(1), window),
                lesson(2, SECTION_A, faculty(2), room(2), window),
                lesson(3, SECTION_A, faculty(3), room(3), window))
            .penalizesBy(2);
    }

    @Test
    void sectionConflict_differentSectionsSameWindow_isNotPenalized() {
        PlannableTimeSlot window = window(1, "MON");
        constraintVerifier.verifyThat(TimetableConstraintProvider::sectionConflict)
            .given(
                lesson(1, SECTION_A, faculty(1), room(1), window),
                lesson(2, SECTION_B, faculty(2), room(2), window))
            .hasNoImpact();
    }

    @Test
    void sectionConflict_unassignedWindow_isIgnored() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::sectionConflict)
            .given(
                lesson(1, SECTION_A, faculty(1), room(1), null),
                lesson(2, SECTION_A, faculty(2), room(2), null))
            .hasNoImpact();
    }

    // ============================ faculty conflict ============================

    @Test
    void facultyConflict_sameFacultySameWindow_isPenalized() {
        PlannableTimeSlot window = window(1, "MON");
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyConflict)
            .given(
                lesson(1, SECTION_A, professor, room(1), window),
                lesson(2, SECTION_B, professor, room(2), window))
            .penalizesBy(1);
    }

    @Test
    void facultyConflict_sameFacultyDifferentWindows_isNotPenalized() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyConflict)
            .given(
                lesson(1, SECTION_A, professor, room(1), window(1, "MON")),
                lesson(2, SECTION_B, professor, room(2), window(2, "TUE")))
            .hasNoImpact();
    }

    @Test
    void facultyConflict_differentFacultySameWindow_isNotPenalized() {
        PlannableTimeSlot window = window(1, "MON");
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyConflict)
            .given(
                lesson(1, SECTION_A, faculty(1), room(1), window),
                lesson(2, SECTION_B, faculty(2), room(2), window))
            .hasNoImpact();
    }

    @Test
    void facultyConflict_missingFaculty_isIgnored() {
        PlannableTimeSlot window = window(1, "MON");
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyConflict)
            .given(
                lesson(1, SECTION_A, null, room(1), window),
                lesson(2, SECTION_B, null, room(2), window))
            .hasNoImpact();
    }

    // ============================= room conflict ==============================

    @Test
    void roomConflict_sameRoomSameWindow_isPenalized() {
        PlannableTimeSlot window = window(1, "MON");
        PlannableRoom classroom = room(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomConflict)
            .given(
                lesson(1, SECTION_A, faculty(1), classroom, window),
                lesson(2, SECTION_B, faculty(2), classroom, window))
            .penalizesBy(1);
    }

    @Test
    void roomConflict_sameRoomDifferentWindows_isNotPenalized() {
        PlannableRoom classroom = room(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomConflict)
            .given(
                lesson(1, SECTION_A, faculty(1), classroom, window(1, "MON")),
                lesson(2, SECTION_B, faculty(2), classroom, window(2, "TUE")))
            .hasNoImpact();
    }

    @Test
    void roomConflict_differentRoomsSameWindow_isNotPenalized() {
        PlannableTimeSlot window = window(1, "MON");
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomConflict)
            .given(
                lesson(1, SECTION_A, faculty(1), room(1), window),
                lesson(2, SECTION_B, faculty(2), room(2), window))
            .hasNoImpact();
    }

    @Test
    void roomConflict_threeLessonsSameRoomSameWindow_penalizesByTwo() {
        PlannableTimeSlot window = window(1, "MON");
        PlannableRoom classroom = room(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomConflict)
            .given(
                lesson(1, SECTION_A, faculty(1), classroom, window),
                lesson(2, SECTION_B, faculty(2), classroom, window),
                lesson(3, 3L, faculty(3), classroom, window))
            .penalizesBy(2);
    }

    @Test
    void roomConflict_missingRoom_isIgnored() {
        PlannableTimeSlot window = window(1, "MON");
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomConflict)
            .given(
                lesson(1, SECTION_A, faculty(1), null, window),
                lesson(2, SECTION_B, faculty(2), null, window))
            .hasNoImpact();
    }

    // ========================== score calculation ===========================

    @Test
    void totalScore_cleanTimetable_isFeasibleZero() {
        HardSoftScore score = constraintVerifier.verifyThat()
            .given(
                lesson(1, SECTION_A, faculty(1), room(1), window(1, "MON")),
                lesson(2, SECTION_A, faculty(2), room(2), window(2, "TUE")))
            .getScore();

        assertEquals(HardSoftScore.ZERO, score);
    }

    @Test
    void totalScore_tripleCollision_sumsThreeHardPenalties() {
        PlannableTimeSlot window = window(1, "MON");
        PlannableFaculty professor = faculty(1);
        PlannableRoom classroom = room(1);

        // l1 and l2 collide on section (both A), faculty (same professor) and
        // room (same classroom) in the same window -> three independent penalties.
        HardSoftScore score = constraintVerifier.verifyThat()
            .given(
                lesson(1, SECTION_A, professor, classroom, window),
                lesson(2, SECTION_A, professor, classroom, window))
            .getScore();

        assertEquals(HardSoftScore.of(-3, 0), score);
    }

    @Test
    void totalScore_mixedScenario_accumulatesAcrossConstraints() {
        PlannableTimeSlot mon = window(1, "MON");
        PlannableTimeSlot tue = window(2, "TUE");
        PlannableFaculty f1 = faculty(1);
        PlannableRoom r2 = room(2);

        // l1 vs l2: same section (A) and same faculty (f1) in MON -> section + faculty = -2
        // l2 vs l3: same room (r2) in MON -> room = -1
        // l4: alone on TUE -> 0
        HardSoftScore score = constraintVerifier.verifyThat()
            .given(
                lesson(1, SECTION_A, f1, room(1), mon),
                lesson(2, SECTION_A, f1, r2, mon),
                lesson(3, SECTION_B, faculty(2), r2, mon),
                lesson(4, 3L, faculty(3), room(3), tue))
            .getScore();

        assertEquals(HardSoftScore.of(-3, 0), score);
    }
}
