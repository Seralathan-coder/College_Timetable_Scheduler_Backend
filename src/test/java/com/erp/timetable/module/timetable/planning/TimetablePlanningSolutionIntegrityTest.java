package com.erp.timetable.module.timetable.planning;

import com.erp.timetable.module.availability.entity.FacultyAvailability;
import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.availability.repository.FacultyAvailabilityRepository;
import com.erp.timetable.module.availability.repository.TimeSlotRepository;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.timetable.engine.shared.SubjectDemandService;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import com.erp.timetable.module.timetable.planning.mapper.SolverResultMapper;
import com.erp.timetable.module.timetable.planning.mapper.TimetablePlanningMapper;
import com.erp.timetable.module.timetable.planning.model.AvailabilityFact;
import com.erp.timetable.module.timetable.planning.model.PlannableFaculty;
import com.erp.timetable.module.timetable.planning.model.PlannableRoom;
import com.erp.timetable.module.timetable.planning.model.PlannableSubject;
import com.erp.timetable.module.timetable.planning.model.PlannableTimeSlot;
import com.erp.timetable.module.timetable.planning.model.PlanningLesson;
import com.erp.timetable.module.timetable.planning.model.SchedulingSolution;
import com.erp.timetable.module.timetable.repository.TimetableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure model-integrity test for the Timefold planning model.
 *
 * Loads the TT1 dataset dump into a dedicated H2 database, maps the domain
 * {@link Timetable} into a {@link SchedulingSolution} via
 * {@link TimetablePlanningMapper}, and validates the integrity of the resulting
 * planning model (lessons, subjects, faculty, rooms, time slots, availability)
 * plus the Database → PlanningSolution → Entities round-trip. SolverManager is
 * never invoked — this is a mapping/model test only.
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties =
    "spring.datasource.url=jdbc:h2:mem:tt1verify;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@Transactional
class TimetablePlanningSolutionIntegrityTest {

    private static final Logger log = LoggerFactory.getLogger(TimetablePlanningSolutionIntegrityTest.class);

    /** TT1 dataset facts (from dataset/tt1-dump.sql). */
    private static final long TT1_TIMETABLE_ID = 1L;
    private static final int EXPECTED_LESSONS = 34;
    private static final int EXPECTED_ROOMS = 2;
    private static final int EXPECTED_FACULTY = 9;
    private static final int EXPECTED_SUBJECTS = 9;
    private static final int EXPECTED_RAW_TIME_SLOTS = 8;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TimetablePlanningMapper timetablePlanningMapper;

    @Autowired
    private SolverResultMapper solverResultMapper;

    @Autowired
    private TimetableRepository timetableRepository;

    @Autowired
    private ClassroomRepository classroomRepository;

    @Autowired
    private TimeSlotRepository timeSlotRepository;

    @Autowired
    private FacultyAvailabilityRepository facultyAvailabilityRepository;

    @BeforeEach
    void loadTt1Dataset() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("RUNSCRIPT FROM 'classpath:dataset/tt1-dump.sql'");
    }

    @Test
    void tt1Dataset_mapsToCompleteAndConsistentPlanningSolution() {
        Timetable timetable = timetableRepository.findById(TT1_TIMETABLE_ID).orElseThrow();
        List<Classroom> rooms = classroomRepository.findAll();
        List<TimeSlot> timeSlots = timeSlotRepository.findAll();
        List<FacultyAvailability> availability = facultyAvailabilityRepository.findAll();

        SchedulingSolution solution = timetablePlanningMapper.toSolution(
            timetable, rooms, timeSlots, availability, SubjectDemandService.WORKING_DAYS);

        printStats(solution, timeSlots);

        assertCompleteSolution(solution, timetable);
        assertLessonIntegrity(solution);
        assertReferenceIntegrity(solution);
        assertNoNullCollections(solution);
        assertRoundTripPreservesIdentity(solution, timetable);
    }

    private void printStats(SchedulingSolution solution, List<TimeSlot> rawTimeSlots) {
        long nonBreak = rawTimeSlots.stream().filter(ts -> !Boolean.TRUE.equals(ts.getIsBreak())).count();
        long planningVariables = (long) solution.getLessons().size() * 2L; // room + timeSlot per lesson

        log.info("══════════ PlanningSolution integrity — TT1 dataset ══════════");
        log.info("lessons            = {} (expected {})", solution.getLessons().size(), EXPECTED_LESSONS);
        log.info("rooms (value range)= {}", solution.getRooms().size());
        log.info("faculty (problem)  = {}", solution.getFaculty().size());
        log.info("subjects (problem) = {}", solution.getSubjects().size());
        log.info("raw time slots     = {} (non-break: {}, working days: {})",
            rawTimeSlots.size(), nonBreak, SubjectDemandService.WORKING_DAYS.size());
        log.info("time-slot windows  = {} (value range {})", solution.getTimeSlots().size(),
            nonBreak * SubjectDemandService.WORKING_DAYS.size());
        log.info("availability facts = {}", solution.getAvailabilityFacts().size());
        log.info("planning variables = {} (2 per lesson: room + timeSlot)", planningVariables);
        log.info("═════════════════════════════════════════════════════════════");
    }

    /**
     * 2. Number of lessons must equal the expected demand; 3. no duplicate ids;
     * 9. no null collections.
     */
    private void assertCompleteSolution(SchedulingSolution solution, Timetable timetable) {
        assertEquals(EXPECTED_LESSONS, solution.getLessons().size(),
            "lesson count must match the TT1 entry demand");
        assertEquals(timetable.getEntries().size(), solution.getLessons().size(),
            "one PlanningLesson per TimetableEntry");

        assertEquals(EXPECTED_ROOMS, solution.getRooms().size(), "room value range must match TT1 classrooms");
        assertEquals(EXPECTED_FACULTY, solution.getFaculty().size(), "faculty problem facts must match TT1 faculty");
        assertEquals(EXPECTED_SUBJECTS, solution.getSubjects().size(),
            "subjects must be the distinct subjects referenced by TT1 entries");
        assertEquals(EXPECTED_RAW_TIME_SLOTS, timeSlotRepository.findAll().size(),
            "raw time-slot count must match TT1");
    }

    /**
     * 1. Every lesson has a subject, a fixed faculty, and a valid block size.
     * 3. No duplicate lesson ids.
     */
    private void assertLessonIntegrity(SchedulingSolution solution) {
        Set<Long> ids = new HashSet<>();
        for (PlanningLesson lesson : solution.getLessons()) {
            assertNotNull(lesson.getSubject(), "lesson " + lesson.getId() + " missing subject");
            assertNotNull(lesson.getFaculty(), "lesson " + lesson.getId() + " missing fixed faculty");

            PlannableSubject subject = lesson.getSubject();
            assertNotNull(subject.getSessionBlockSize(), "subject " + subject.getSubjectCode() + " has null block size");
            assertTrue(subject.getSessionBlockSize() >= 1,
                "subject " + subject.getSubjectCode() + " invalid block size " + subject.getSessionBlockSize());
            assertTrue(subject.getSessionBlockSize() <= subject.getWeeklyHours(),
                "subject " + subject.getSubjectCode() + " block size " + subject.getSessionBlockSize()
                    + " exceeds weekly hours " + subject.getWeeklyHours());

            assertTrue(ids.add(lesson.getId()), "duplicate lesson id: " + lesson.getId());
        }
        assertEquals(solution.getLessons().size(), ids.size(), "lesson ids must be unique");
    }

    /**
     * 4. Every lesson references an existing subject.
     * 5. Every subject references an existing faculty (when assigned).
     * 6. Every room exists.
     * 7. Every time-slot window exists.
     * 8. Every availability fact references an existing faculty.
     */
    private void assertReferenceIntegrity(SchedulingSolution solution) {
        Map<Long, PlannableSubject> subjectsById = solution.getSubjects().stream()
            .collect(Collectors.toMap(PlannableSubject::getSubjectId, Function.identity()));
        Map<Long, PlannableFaculty> facultyById = solution.getFaculty().stream()
            .collect(Collectors.toMap(PlannableFaculty::getFacultyId, Function.identity()));
        Map<Long, PlannableRoom> roomsById = solution.getRooms().stream()
            .collect(Collectors.toMap(PlannableRoom::getRoomId, Function.identity()));
        Map<String, PlannableTimeSlot> windowsByKey = solution.getTimeSlots().stream()
            .collect(Collectors.toMap(t -> t.getTimeSlotId() + "|" + t.getDayOfWeek(), Function.identity()));

        for (PlanningLesson lesson : solution.getLessons()) {
            assertTrue(subjectsById.containsKey(lesson.getSubject().getSubjectId()),
                "lesson " + lesson.getId() + " references missing subject " + lesson.getSubject().getSubjectId());
        }

        for (PlannableSubject subject : solution.getSubjects()) {
            if (subject.getAssignedFacultyId() != null) {
                assertTrue(facultyById.containsKey(subject.getAssignedFacultyId()),
                    "subject " + subject.getSubjectCode() + " references missing faculty "
                        + subject.getAssignedFacultyId());
            }
        }

        for (PlanningLesson lesson : solution.getLessons()) {
            assertNotNull(lesson.getRoom(), "lesson " + lesson.getId() + " has no room");
            assertTrue(roomsById.containsKey(lesson.getRoom().getRoomId()),
                "lesson " + lesson.getId() + " references missing room " + lesson.getRoom().getRoomId());

            assertNotNull(lesson.getTimeSlot(), "lesson " + lesson.getId() + " has no time slot");
            String key = lesson.getTimeSlot().getTimeSlotId() + "|" + lesson.getTimeSlot().getDayOfWeek();
            assertTrue(windowsByKey.containsKey(key),
                "lesson " + lesson.getId() + " references missing window " + key);
        }

        for (AvailabilityFact fact : solution.getAvailabilityFacts()) {
            assertTrue(facultyById.containsKey(fact.getFacultyId()),
                "availability fact references missing faculty " + fact.getFacultyId());
        }
    }

    /**
     * 9. The solution must not contain null collections.
     */
    private void assertNoNullCollections(SchedulingSolution solution) {
        assertNotNull(solution.getLessons(), "lessons collection must not be null");
        assertNotNull(solution.getSubjects(), "subjects collection must not be null");
        assertNotNull(solution.getFaculty(), "faculty collection must not be null");
        assertNotNull(solution.getAvailabilityFacts(), "availabilityFacts collection must not be null");
        assertNotNull(solution.getRooms(), "rooms collection must not be null");
        assertNotNull(solution.getTimeSlots(), "timeSlots collection must not be null");
    }

    /**
     * 10. Database → PlanningSolution → Entities preserves identity: every lesson
     * must mirror its source {@link TimetableEntry} (ids, subject, faculty, room,
     * day, slot, section, flags), and applying the solution back through
     * {@link SolverResultMapper} must reproduce the same entries.
     */
    private void assertRoundTripPreservesIdentity(SchedulingSolution solution, Timetable timetable) {
        Map<Long, TimetableEntry> entriesById = new HashMap<>();
        timetable.getEntries().forEach(e -> entriesById.put(e.getId(), e));
        assertEquals(entriesById.size(), solution.getLessons().size(),
            "every source entry must map to exactly one lesson");

        for (PlanningLesson lesson : solution.getLessons()) {
            TimetableEntry entry = entriesById.get(lesson.getSourceEntryId());
            assertNotNull(entry, "lesson " + lesson.getId() + " has no source entry "
                + lesson.getSourceEntryId());

            assertEquals(entry.getId(), lesson.getId(), "lesson id must equal source entry id");
            assertEquals(entry.getSubject().getId(), lesson.getSubject().getSubjectId(),
                "lesson subject must match source entry");
            assertEquals(entry.getFaculty().getId(), lesson.getFaculty().getFacultyId(),
                "lesson faculty must match source entry");
            assertEquals(entry.getClassroom().getId(), lesson.getRoom().getRoomId(),
                "lesson room must match source entry");
            assertEquals(entry.getDayOfWeek(), lesson.getTimeSlot().getDayOfWeek(),
                "lesson day must match source entry");
            assertEquals(entry.getTimeSlot().getId(), lesson.getTimeSlot().getTimeSlotId(),
                "lesson slot must match source entry");
            assertEquals(entry.getSection().getId(), lesson.getSectionId(),
                "lesson section must match source entry");
            assertEquals(Boolean.TRUE.equals(entry.getIsLab()), lesson.isLab(),
                "lesson lab flag must match source entry");
            assertEquals(Boolean.TRUE.equals(entry.getIsLocked()), lesson.isLocked(),
                "lesson locked flag must match source entry");
        }

        // Round-trip: applying the solution must not add/remove/mutate identity.
        int entryCountBefore = timetable.getEntries().size();
        solverResultMapper.applyToTimetable(timetable, solution);
        assertEquals(entryCountBefore, timetable.getEntries().size(),
            "round-trip must not create or drop entries");

        for (PlanningLesson lesson : solution.getLessons()) {
            TimetableEntry entry = entriesById.get(lesson.getSourceEntryId());
            assertEquals(lesson.getRoom().getRoomId(), entry.getClassroom().getId(),
                "round-trip must preserve room assignment");
            assertEquals(lesson.getTimeSlot().getDayOfWeek(), entry.getDayOfWeek(),
                "round-trip must preserve day assignment");
            assertEquals(lesson.getTimeSlot().getTimeSlotId(), entry.getTimeSlot().getId(),
                "round-trip must preserve slot assignment");
        }
    }
}
