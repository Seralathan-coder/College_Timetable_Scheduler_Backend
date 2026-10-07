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
 * Tests for {@code TimetableConstraintProvider#facultyWeeklyHoursLimit} — the
 * Timefold translation of the Greedy {@code FacultyWeeklyHoursConstraint}
 * (cap = faculty maxWeeklyHours, default 24; each excess period costs one hard
 * point).
 */
class FacultyWeeklyHoursConstraintTest {

    private final ConstraintVerifier<TimetableConstraintProvider, SchedulingSolution> constraintVerifier =
        ConstraintVerifier.build(new TimetableConstraintProvider(), SchedulingSolution.class, PlanningLesson.class);

    private static final PlannableRoom ROOM = room(1);
    private static final PlannableSubject SUBJECT = subject(1, "PHY101", "THEORY", 1L);
    private static final String[] DAYS = {"MON", "TUE", "WED", "THU", "FRI", "SAT"};

    private static PlanningLesson lessonOnDay(int id, PlannableFaculty professor, String day) {
        return lesson(id, 1, professor, SUBJECT, ROOM, window(id, day, 1));
    }

    @Test
    void oneLessonBelowCap_isNotPenalized() {
        PlannableFaculty professor = faculty(1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyWeeklyHoursLimit)
            .given(lessonOnDay(1, professor, "MON"))
            .hasNoImpact();
    }

    @Test
    void weeklyCapUnset_defaultsTo24() {
        PlannableFaculty professor = faculty(1);
        PlanningLesson[] lessons = new PlanningLesson[25];
        for (int i = 0; i < 25; i++) {
            lessons[i] = lesson(i + 1, 1, professor, SUBJECT, ROOM,
                window(i + 1, DAYS[i % DAYS.length], (i / DAYS.length) + 1));
        }
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyWeeklyHoursLimit)
            .given(lessons)
            .penalizesBy(1);
    }

    @Test
    void twentyFourLessons_atDefaultCap_isNotPenalized() {
        PlannableFaculty professor = faculty(1);
        PlanningLesson[] lessons = new PlanningLesson[24];
        for (int i = 0; i < 24; i++) {
            lessons[i] = lesson(i + 1, 1, professor, SUBJECT, ROOM,
                window(i + 1, DAYS[i % DAYS.length], (i / DAYS.length) + 1));
        }
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyWeeklyHoursLimit)
            .given(lessons)
            .hasNoImpact();
    }

    @Test
    void facultyCapTen_elevenLessons_penalizesByOne() {
        PlannableFaculty professor = faculty(1, 6, 10);
        PlanningLesson[] lessons = new PlanningLesson[11];
        for (int i = 0; i < 11; i++) {
            lessons[i] = lesson(i + 1, 1, professor, SUBJECT, ROOM,
                window(i + 1, DAYS[i % DAYS.length], (i / DAYS.length) + 1));
        }
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyWeeklyHoursLimit)
            .given(lessons)
            .penalizesBy(1);
    }

    @Test
    void facultyCapTen_tenLessons_atCap_isNotPenalized() {
        PlannableFaculty professor = faculty(1, 6, 10);
        PlanningLesson[] lessons = new PlanningLesson[10];
        for (int i = 0; i < 10; i++) {
            lessons[i] = lesson(i + 1, 1, professor, SUBJECT, ROOM,
                window(i + 1, DAYS[i % DAYS.length], (i / DAYS.length) + 1));
        }
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyWeeklyHoursLimit)
            .given(lessons)
            .hasNoImpact();
    }

    @Test
    void facultyCapNonPositive_defaultsTo24() {
        PlannableFaculty professor = faculty(1, 6, 0);
        PlanningLesson[] lessons = new PlanningLesson[5];
        for (int i = 0; i < 5; i++) {
            lessons[i] = lessonOnDay(i + 1, professor, DAYS[i]);
        }
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyWeeklyHoursLimit)
            .given(lessons)
            .hasNoImpact();
    }

    @Test
    void unscheduledLessons_areIgnored() {
        PlannableFaculty professor = faculty(1, 6, 1);
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyWeeklyHoursLimit)
            .given(
                lesson(1, 1, professor, SUBJECT, null, null),
                lesson(2, 1, professor, SUBJECT, null, null))
            .hasNoImpact();
    }

    @Test
    void missingFacultyLessons_areIgnored() {
        constraintVerifier.verifyThat(TimetableConstraintProvider::facultyWeeklyHoursLimit)
            .given(
                lesson(1, 1, null, SUBJECT, ROOM, window(1, "MON", 1)),
                lesson(2, 1, null, SUBJECT, ROOM, window(2, "TUE", 1)))
            .hasNoImpact();
    }
}
