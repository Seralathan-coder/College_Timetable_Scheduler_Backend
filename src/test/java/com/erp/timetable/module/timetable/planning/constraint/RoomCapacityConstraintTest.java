package com.erp.timetable.module.timetable.planning.constraint;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
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
 * Tests for {@code TimetableConstraintProvider#roomCapacity} — the Timefold
 * translation of the Greedy room-capacity filter (room capacity must be &gt;=
 * required capacity, where required capacity is the section's student strength
 * and defaults to 40).
 */
class RoomCapacityConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableSubject SUBJECT = subject(1, "PHY101", "THEORY", 1L);

    private static PlanningLesson capLesson(long lessonId, Integer roomCapacity, Integer requiredCapacity) {
        return lesson(lessonId, 1, faculty(lessonId), SUBJECT,
            faculty(lessonId).getDepartmentId(), requiredCapacity,
            room(lessonId, "LECTURE_HALL", roomCapacity), window(lessonId, "MON", 1));
    }

    @Test
    void capacityMeetsRequirement_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomCapacity)
            .given(capLesson(1, 60, 40))
            .hasNoImpact();
    }

    @Test
    void capacityExactlyRequired_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomCapacity)
            .given(capLesson(1, 40, 40))
            .hasNoImpact();
    }

    @Test
    void capacityBelowRequirement_isPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomCapacity)
            .given(capLesson(1, 30, 40))
            .penalizesBy(1);
    }

    @Test
    void sectionStrengthAboveRoomCapacity_isPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomCapacity)
            .given(capLesson(1, 40, 60))
            .penalizesBy(1);
    }

    @Test
    void capacityWellAboveRequirement_isNotPenalized() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomCapacity)
            .given(capLesson(1, 80, 40))
            .hasNoImpact();
    }

    @Test
    void nullRoom_isIgnored() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomCapacity)
            .given(lesson(1, 1, faculty(1), SUBJECT, null, null))
            .hasNoImpact();
    }

    @Test
    void nullRoomCapacity_isIgnored() {
        PlanningLesson lesson = PlanningLesson.builder()
            .id(1L)
            .sectionId(1L)
            .faculty(faculty(1))
            .subject(SUBJECT)
            .departmentId(1L)
            .requiredCapacity(40)
            .room(room(1, "LECTURE_HALL", null))
            .timeSlot(window(1, "MON", 1))
            .build();
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomCapacity)
            .given(lesson)
            .hasNoImpact();
    }

    @Test
    void nullRequiredCapacity_defaultsToForty() {
        PlanningLesson lesson = PlanningLesson.builder()
            .id(1L)
            .sectionId(1L)
            .faculty(faculty(1))
            .subject(SUBJECT)
            .departmentId(1L)
            .requiredCapacity(null)
            .room(room(1, "LECTURE_HALL", 30))
            .timeSlot(window(1, "MON", 1))
            .build();
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomCapacity)
            .given(lesson)
            .penalizesBy(1);
    }

    @Test
    void twoOvercapacityLessons_penalizeByTwo() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::roomCapacity)
            .given(
                capLesson(1, 30, 40),
                capLesson(2, 20, 40))
            .penalizesBy(2);
    }
}
