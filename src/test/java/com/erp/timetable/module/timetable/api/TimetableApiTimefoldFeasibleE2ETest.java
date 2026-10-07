package com.erp.timetable.module.timetable.api;

import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.availability.repository.TimeSlotRepository;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.department.repository.SectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 — API end-to-end verification on a <em>feasible</em> dataset.
 *
 * <p>Uses the seeded master data (CSE section A, semester 3) with the section
 * strength lowered to 40 so the seeded LAB room (capacity 40) satisfies the
 * room-capacity constraint. Through the real {@code POST /generate} endpoint the
 * Timefold engine must produce a fully feasible timetable: zero conflicts, every
 * curriculum lesson scheduled (CS201×3 + CS202×3 + CS205L×3 = 9 entries) and the
 * lab placed as three single-period sessions in the LAB room (CS205L carries the
 * DB-default sessionBlockSize of 1, honored verbatim).
 *
 * <p>This is the same dataset the {@code TimefoldScheduleEngineIntegrationTest}
 * proves feasible, but driven through the full REST contract (controller →
 * service → engine → solver → result mapper → persistence → response).
 */
@TestPropertySource(properties = {
    "timetable.scheduler.engine=timefold",
    "spring.datasource.url=jdbc:h2:mem:tt1api_feasible;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
class TimetableApiTimefoldFeasibleE2ETest extends AbstractTimetableApiE2E {

    private static final String SESSION = "2025-2026 EVEN";

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private SectionRepository sectionRepository;

    @Autowired
    private ClassroomRepository classroomRepository;

    @Autowired
    private TimeSlotRepository timeSlotRepository;

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
        return 9; // unused on the feasible dataset, kept for the abstract contract
    }

    @Test
    void generate_feasibleSeed_throughApi_isFullyFeasible(CapturedOutput output) throws Exception {
        Department cse = departmentRepository.findByName("Computer Science & Engineering").orElseThrow();
        AcademicYear year1 = cse.getAcademicYears().get(0);
        Section secA = year1.getSections().get(0);
        secA.setStudentStrength(40); // seeded LAB room capacity is 40
        sectionRepository.saveAndFlush(secA);

        long apiStart = System.nanoTime();
        JsonNode data = postGenerateExpectSuccess(cse.getId(), secA.getId(), 3, SESSION);
        long apiMs = Duration.ofNanos(System.nanoTime() - apiStart).toMillis();

        // Fully feasible through the API.
        assertEquals("GENERATED", data.get("status").asText());
        assertEquals(0, data.get("conflictCount").asInt(),
            "a feasible solve must record no conflicts: " + data.get("conflicts"));
        assertTrue(data.get("conflicts").isEmpty(),
            "conflict list must be empty on a feasible solve: " + data.get("conflicts"));
        assertEquals(9, data.get("entries").size(),
            "CS201(3) + CS202(3) + CS205L lab(3) = 9 curriculum lessons");
        assertNoWindowClashes(data.get("entries"));
        int optimization = data.get("optimizationScore").asInt();
        assertTrue(optimization >= 0 && optimization <= 100,
            "optimization score out of range: " + optimization);

        // Lab (CS205L: 3 practical hours) carries the DB-default sessionBlockSize
        // of 1, which is honored verbatim: each practical hour is placed as a
        // separate single-period session, all in one LAB room.
        List<JsonNode> labEntries = new ArrayList<>();
        for (JsonNode e : data.get("entries")) {
            if ("LAB".equalsIgnoreCase(e.get("subjectType").asText())) {
                labEntries.add(e);
            }
        }
        assertEquals(3, labEntries.size(), "the LAB subject must be placed as exactly 3 practical periods");
        assertEquals(1, labEntries.stream()
                .map(e -> e.get("classroomId").asText()).collect(Collectors.toSet()).size(),
            "all practical periods must use a single room");

        long labRoomId = labEntries.get(0).get("classroomId").asLong();
        Classroom labRoom = classroomRepository.findById(labRoomId).orElseThrow();
        assertEquals("LAB", labRoom.getRoomType(), "practical periods must use a LAB room");

        // Consecutive runs across the practical periods: sessionBlockSize 1 means
        // every session is exactly one period (never a run of 2+).
        List<Integer> runSizes = new ArrayList<>();
        Map<String, List<Integer>> byDay = labEntries.stream().collect(Collectors.groupingBy(
            e -> e.get("dayOfWeek").asText(),
            Collectors.mapping(e -> timeSlotRepository.findById(e.get("timeSlotId").asLong()).orElseThrow()
                    .getSlotOrder(), Collectors.toList())));
        for (List<Integer> orders : byDay.values()) {
            List<Integer> sorted = orders.stream().sorted().toList();
            int runStart = 0;
            for (int i = 1; i <= sorted.size(); i++) {
                if (i == sorted.size() || sorted.get(i) != sorted.get(i - 1) + 1) {
                    runSizes.add(i - runStart);
                    runStart = i;
                }
            }
        }
        assertEquals(List.of(1, 1, 1), runSizes,
            "3 practical hours with sessionBlockSize 1 must form three 1-period sessions: " + runSizes);

        // Timing breakdown from the engine logs + persistence approximation.
        String logs = output.getAll();
        long engineMs = parseMillis(logs, "Engine total");
        long solverMs = parseMillis(logs, "Solved in");
        long mappingMs = parseMillis(logs, "Prepared planning model in");
        long applyMs = parseMillis(logs, "Applied solver result in");
        long persistenceApprox = Math.max(0L, apiMs - engineMs);
        recordGenerateMetric(engineName(), apiMs, engineMs, solverMs, mappingMs, applyMs,
            persistenceApprox, data);
        assertDurationUnder("API generate (feasible)", apiMs, 30_000);
    }
}
