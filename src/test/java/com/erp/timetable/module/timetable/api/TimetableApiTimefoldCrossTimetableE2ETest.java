package com.erp.timetable.module.timetable.api;

import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.department.repository.SectionRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7 — cross-timetable clash avoidance end-to-end.
 *
 * <p>Uses the seeded master data (CSE) with TWO sections: section A (semester
 * 3, the seeded CS201/CS202/CS205L curriculum) and section B (semester 3, a
 * curriculum added by the test that shares the same faculty F1/F2 and the same
 * lecture halls). Through the real {@code POST /generate} endpoint the Timefold
 * engine must never place B's lesson on a (faculty, day, slot) or
 * (room, day, slot) window that A's timetable already occupies — the Phase 7
 * {@code Cross-timetable occupancy conflict} hard constraint fed by the
 * {@code occupancyFacts} problem facts. The regeneration test proves partial
 * regeneration of B keeps A untouched and still honours A's occupancy.
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=timefold",
    "spring.datasource.url=jdbc:h2:mem:tt1api_xsection;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class TimetableApiTimefoldCrossTimetableE2ETest extends AbstractTimetableApiE2E {

    private static final String SESSION = "2025-2026 EVEN";

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private SectionRepository sectionRepository;

    @Autowired
    private FacultyRepository facultyRepository;

    @Autowired
    private SubjectRepository subjectRepository;

    /** Keep the seeded master data (do not load the TT1 dump). */
    @Override
    protected void loadDataset() {
    }

    @Override
    protected String engineName() {
        return "timefold";
    }

    @Override
    protected int expectedTt1Entries() {
        return 9; // unused on the seeded dataset, kept for the abstract contract
    }

    @Test
    void generateSecondSection_avoidsOccupiedWindowsOfFirstTimetable() throws Exception {
        Setup setup = prepareSectionsAndSubjects();

        JsonNode first = postGenerateExpectSuccess(setup.cseId, setup.secA.getId(), 3, SESSION);
        assertEquals(9, first.get("entries").size(), "section A must schedule its 9 curriculum lessons");
        assertEquals(0, first.get("conflictCount").asInt());

        JsonNode second = postGenerateExpectSuccess(setup.cseId, setup.secB.getId(), 3, SESSION);
        assertEquals("GENERATED", second.get("status").asText());
        assertEquals(9, second.get("entries").size(), "section B must schedule all 9 curriculum lessons");
        assertEquals(0, second.get("conflictCount").asInt(),
            "section B must solve with a zero hard score: " + second.get("conflicts"));
        assertNoWindowClashes(second.get("entries"));
        assertNoClashesWithOther(second.get("entries"), first.get("entries"));
    }

    @Test
    void regenerateSecondSection_preservesFirstTimetable_andStillAvoidsItsWindows() throws Exception {
        Setup setup = prepareSectionsAndSubjects();

        JsonNode first = postGenerateExpectSuccess(setup.cseId, setup.secA.getId(), 3, SESSION);
        assertEquals(9, first.get("entries").size());

        long firstId = first.get("id").asLong();
        long lockedEntryId = first.get("entries").get(0).get("id").asLong();
        lockEntry(lockedEntryId);

        JsonNode second = postGenerateExpectSuccess(setup.cseId, setup.secB.getId(), 3, SESSION);
        assertEquals(9, second.get("entries").size());
        long secondId = second.get("id").asLong();

        JsonNode regenerated = regenerateUnlocked(secondId);
        assertEquals(9, regenerated.get("entries").size(), "regeneration must re-schedule all of B");
        assertEquals(0, regenerated.get("conflictCount").asInt());
        assertNoClashesWithOther(regenerated.get("entries"), first.get("entries"));

        JsonNode firstAfter = getTimetable(firstId);
        assertEquals(9, firstAfter.get("entries").size(), "regenerating B must not touch A");
        assertEquals(first.get("entries").size(), firstAfter.get("entries").size());
        Set<String> lockedPlacement = placementOf(first.get("entries").get(0));
        boolean lockedStillPresent = false;
        for (JsonNode e : firstAfter.get("entries")) {
            if (e.get("id").asLong() == lockedEntryId) {
                lockedStillPresent = true;
                assertEquals(lockedPlacement, placementOf(e),
                    "A's locked entry must keep its exact placement");
            }
        }
        assertTrue(lockedStillPresent, "A's locked entry must survive B's regeneration");
    }

    @Test
    void twoTimetables_sharingFaculty_respectTheDailyCapGlobally() throws Exception {
        Setup setup = prepareSectionsAndSubjects();

        JsonNode first = postGenerateExpectSuccess(setup.cseId, setup.secA.getId(), 3, SESSION);
        JsonNode second = postGenerateExpectSuccess(setup.cseId, setup.secB.getId(), 3, SESSION);

        // Phase 7: the college-wide 5/day cap (or the faculty's own lower
        // maxDailyHours) applies to a faculty's load across EVERY timetable,
        // not per section. The seeded FAC001/FAC002 cap themselves at 4/day.
        Map<Long, Integer> effectiveDailyCaps = new HashMap<>();
        for (Faculty f : facultyRepository.findAll()) {
            Integer own = f.getMaxDailyHours();
            effectiveDailyCaps.put(f.getId(),
                (own != null && own > 0) ? Math.min(5, own) : 5);
        }

        Map<Long, Map<String, Integer>> combinedDailyLoad = new HashMap<>();
        for (JsonNode timetable : List.of(first, second)) {
            for (JsonNode e : timetable.get("entries")) {
                combinedDailyLoad.computeIfAbsent(e.get("facultyId").asLong(), k -> new HashMap<>())
                    .merge(e.get("dayOfWeek").asText(), 1, Integer::sum);
            }
        }

        for (Map.Entry<Long, Map<String, Integer>> byFaculty : combinedDailyLoad.entrySet()) {
            int cap = effectiveDailyCaps.getOrDefault(byFaculty.getKey(), 5);
            for (Map.Entry<String, Integer> day : byFaculty.getValue().entrySet()) {
                assertTrue(day.getValue() <= cap,
                    "faculty " + byFaculty.getKey() + " teaches " + day.getValue()
                        + " periods on " + day.getKey() + " across both timetables, exceeding cap " + cap);
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Setup prepareSectionsAndSubjects() throws Exception {
        Department cse = departmentRepository.findByName("Computer Science & Engineering").orElseThrow();
        AcademicYear year1 = cse.getAcademicYears().get(0);
        Section secA = year1.getSections().get(0);
        Section secB = year1.getSections().get(1);
        secA.setStudentStrength(40); // seeded LAB room capacity is 40
        secB.setStudentStrength(40);
        sectionRepository.saveAndFlush(secA);
        sectionRepository.saveAndFlush(secB);

        Faculty f1 = facultyRepository.findByEmployeeId("FAC001").orElseThrow();
        Faculty f2 = facultyRepository.findByEmployeeId("FAC002").orElseThrow();

        // Section B's own curriculum, sharing F1/F2 and the CSE lecture halls.
        if (subjectRepository.findBySubjectCode("CS301").isEmpty()) {
            subjectRepository.save(Subject.builder().subjectCode("CS301")
                .subjectName("Operating Systems").department(cse).academicYear(year1).section(secB)
                .assignedFaculty(f1).semester(3).credits(4).theoryHours(3).practicalHours(0)
                .subjectType("THEORY").isActive(true).build());
            subjectRepository.save(Subject.builder().subjectCode("CS302")
                .subjectName("Computer Networks").department(cse).academicYear(year1).section(secB)
                .assignedFaculty(f2).semester(3).credits(4).theoryHours(3).practicalHours(0)
                .subjectType("THEORY").isActive(true).build());
            subjectRepository.save(Subject.builder().subjectCode("CS303")
                .subjectName("Software Engineering").department(cse).academicYear(year1).section(secB)
                .assignedFaculty(f2).semester(3).credits(4).theoryHours(3).practicalHours(0)
                .subjectType("THEORY").isActive(true).build());
        }

        return new Setup(cse.getId(), secA, secB);
    }

    private JsonNode regenerateUnlocked(long timetableId) throws Exception {
        MvcResult result = mockMvc.perform(post("/timetable/" + timetableId + "/regenerate-unlocked")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(root.get("success").asBoolean(), "regenerate-unlocked must succeed: " + root);
        return root.get("data");
    }

    private JsonNode lockEntry(long entryId) throws Exception {
        MvcResult result = mockMvc.perform(patch("/timetable/entries/" + entryId + "/lock")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(root.get("success").asBoolean(), "lock toggle must succeed: " + root);
        return root.get("data");
    }

    private JsonNode getTimetable(long timetableId) throws Exception {
        MvcResult result = mockMvc.perform(get("/timetable/" + timetableId))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    /** No candidate entry may reuse a (faculty, day, slot) or (room, day, slot)
     * window occupied by the other timetable. */
    private void assertNoClashesWithOther(JsonNode candidate, JsonNode occupied) {
        Set<String> facultyWindows = new HashSet<>();
        Set<String> roomWindows = new HashSet<>();
        for (JsonNode e : occupied) {
            String window = e.get("dayOfWeek").asText() + "|" + e.get("timeSlotId").asText();
            facultyWindows.add(e.get("facultyId").asText() + "|" + window);
            roomWindows.add(e.get("classroomId").asText() + "|" + window);
        }
        for (JsonNode e : candidate) {
            String window = e.get("dayOfWeek").asText() + "|" + e.get("timeSlotId").asText();
            assertTrue(facultyWindows.add(e.get("facultyId").asText() + "|" + window),
                "candidate reuses a faculty (day, slot) window occupied by the other timetable: " + e);
            assertTrue(roomWindows.add(e.get("classroomId").asText() + "|" + window),
                "candidate reuses a room (day, slot) window occupied by the other timetable: " + e);
        }
    }

    private Set<String> placementOf(JsonNode e) {
        Set<String> placement = new HashSet<>();
        placement.add(e.get("dayOfWeek").asText());
        placement.add(e.get("timeSlotId").asText());
        placement.add(e.get("facultyId").asText());
        placement.add(e.get("classroomId").asText());
        return placement;
    }

    private record Setup(long cseId, Section secA, Section secB) {
    }
}
