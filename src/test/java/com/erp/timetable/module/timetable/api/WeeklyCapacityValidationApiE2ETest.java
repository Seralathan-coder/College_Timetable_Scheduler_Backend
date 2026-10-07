package com.erp.timetable.module.timetable.api;

import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Weekly-capacity validation through the real {@code POST /timetable/generate}
 * REST contract (controller → service pre-validation → engine → persistence).
 *
 * <p>Uses the seeded master data (CSE 1st Year Section A) with a synthetic
 * theory-only curriculum for the otherwise-unused semester 1, so the total
 * weekly demand is controlled precisely. The validation layer is generic — it
 * is driven by the actual subject hours and the configured TimeSlot/workday
 * master, never by department/section names, so this same CSE section is just
 * a vehicle for arbitrary demand figures.
 *
 * <p>Assertions target ONLY the capacity-validation behaviour (which is
 * deterministic and independent of engine placement quality): the
 * {@code CAPACITY_EXCEEDED} conflict must appear iff demand &gt; capacity, and
 * a slot must never be overbooked. Engine placement details are out of scope.
 */
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:tt1api_capacity_validation;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class WeeklyCapacityValidationApiE2ETest extends AbstractTimetableApiE2E {

    private static final String SESSION = "2025-2026 EVEN";
    private static final int TEST_SEMESTER = 1;

    private static int subjectCodeSeq = 0;

    @Autowired
    private DepartmentRepository departmentRepository;

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
        return "greedy";
    }

    @Override
    protected int expectedTt1Entries() {
        return 0; // unused on the synthetic curriculum, kept for the abstract contract
    }

    @Test
    void demandBelowCapacity_isAllowed_withoutCapacityConflict() throws Exception {
        Section section = cseSectionA();
        createTheorySubjects(section, 35);

        JsonNode data = postGenerateExpectSuccess(section.getAcademicYear().getDepartment().getId(),
            section.getId(), TEST_SEMESTER, SESSION);

        assertEquals("GENERATED", data.get("status").asText());
        assertNoCapacityExceededConflict(data);
        assertNoWindowClashes(data.get("entries"));
        assertTrue(data.get("entries").size() <= 42,
            "never more scheduled lessons than the weekly capacity: " + data.get("entries").size());
    }

    @Test
    void demandEqualCapacity_isAllowed_withoutCapacityConflict() throws Exception {
        Section section = cseSectionA();
        createTheorySubjects(section, 42);

        JsonNode data = postGenerateExpectSuccess(section.getAcademicYear().getDepartment().getId(),
            section.getId(), TEST_SEMESTER, SESSION);

        assertEquals("GENERATED", data.get("status").asText());
        assertNoCapacityExceededConflict(data);
        assertNoWindowClashes(data.get("entries"));
        assertTrue(data.get("entries").size() <= 42,
            "never more scheduled lessons than the weekly capacity: " + data.get("entries").size());
    }

    @Test
    void demandAboveCapacity_isMarkedWithCapacityExceeded_andNeverOverbooks() throws Exception {
        Section section = cseSectionA();
        createTheorySubjects(section, 43);

        JsonNode data = postGenerateExpectSuccess(section.getAcademicYear().getDepartment().getId(),
            section.getId(), TEST_SEMESTER, SESSION);

        assertEquals("GENERATED", data.get("status").asText());

        String description = assertCapacityExceededConflict(data);
        assertTrue(description.contains("43 required periods"),
            "message must state the real demand: " + description);
        assertTrue(description.contains("42 available slots"),
            "message must state the configured capacity (6 working days x 7 teaching slots): " + description);

        assertTrue(data.get("conflictCount").asInt() >= 1,
            "an over-capacity schedule must not report zero conflicts");
        assertNoWindowClashes(data.get("entries"));
        assertTrue(data.get("entries").size() <= 42,
            "an over-capacity schedule must never overbook a slot: " + data.get("entries").size());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** CSE 1st Year, Section A — the vehicle for arbitrary demand scenarios. */
    private Section cseSectionA() {
        Department cse = departmentRepository.findByName("Computer Science & Engineering").orElseThrow();
        AcademicYear year1 = cse.getAcademicYears().get(0);
        assertEquals("A", year1.getSections().get(0).getName(), "test expects the seeded CSE Section A");
        return year1.getSections().get(0);
    }

    /**
     * Creates theory-only subjects (sessionBlockSize 1) on the section for
     * {@link #TEST_SEMESTER} whose combined weekly hours sum to
     * {@code totalDemand}. Codes/names are arbitrary so the test proves the
     * calculation never depends on department/section/subject names.
     */
    private List<Subject> createTheorySubjects(Section section, int totalDemand) {
        Department dept = section.getAcademicYear().getDepartment();
        AcademicYear year = section.getAcademicYear();
        Faculty f1 = facultyRepository.findByEmployeeId("FAC001").orElse(null);
        Faculty f2 = facultyRepository.findByEmployeeId("FAC002").orElse(null);

        List<Subject> created = new ArrayList<>();
        int remaining = totalDemand;
        int index = 0;
        while (remaining > 0) {
            int hours = Math.min(3, remaining);
            Subject subject = Subject.builder()
                .subjectCode("CAPV" + (subjectCodeSeq++) + "_SUBJ" + index)
                .subjectName("Weekly-Capacity Test Subject " + index)
                .department(dept)
                .academicYear(year)
                .section(section)
                .assignedFaculty(index % 2 == 0 ? f1 : f2)
                .semester(TEST_SEMESTER)
                .credits(hours)
                .theoryHours(hours)
                .practicalHours(0)
                .subjectType("THEORY")
                .sessionBlockSize(1)
                .isActive(true)
                .build();
            created.add(subjectRepository.save(subject));
            remaining -= hours;
            index++;
        }
        subjectRepository.flush();
        return created;
    }

    private void assertNoCapacityExceededConflict(JsonNode data) {
        JsonNode conflicts = data.get("conflicts");
        assertNotNull(conflicts, "response must expose conflicts");
        for (JsonNode conflict : conflicts) {
            assertFalse("CAPACITY_EXCEEDED".equals(conflict.get("conflictType").asText()),
                "demand within capacity must not produce a capacity-exceeded conflict: " + conflicts);
        }
    }

    private String assertCapacityExceededConflict(JsonNode data) {
        for (JsonNode conflict : data.get("conflicts")) {
            if ("CAPACITY_EXCEEDED".equals(conflict.get("conflictType").asText())) {
                return conflict.get("description").asText();
            }
        }
        throw new AssertionError("expected a CAPACITY_EXCEEDED conflict, got: " + data.get("conflicts")
            + " conflictCount=" + data.get("conflictCount"));
    }
}