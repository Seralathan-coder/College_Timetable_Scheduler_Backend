package com.erp.timetable.module.timetable.api;

import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-level end-to-end verification of the real TT1 dataset through the REST
 * contract. These tests run identically for every engine subclass (Greedy and
 * Timefold) so the two engines can be compared on the same dataset.
 */
public abstract class AbstractTimetableApiTt1E2E extends AbstractTimetableApiE2E {

    private static final String SESSION = "2025-2026 EVEN";

    @Test
    void generate_tt1_keepsApiContract_andSchedulesWithoutClashes(CapturedOutput output) throws Exception {
        long apiStart = System.nanoTime();
        JsonNode data = postGenerateExpectSuccess(TT1_DEPARTMENT_ID, TT1_SECTION_ID, TT1_SEMESTER, SESSION);
        long apiMs = Duration.ofNanos(System.nanoTime() - apiStart).toMillis();

        assertEquals("GENERATED", data.get("status").asText());
        assertEqualsEntries(expectedTt1Entries(), data);
        assertFalse(data.get("entries").isEmpty());
        for (JsonNode e : data.get("entries")) {
            assertEntryShape(e);
        }
        assertNoWindowClashes(data.get("entries"));
        assertDurationUnder("API generate", apiMs, 30_000);

        // Engine timing breakdown (only Timefold emits these logs).
        String logs = output.getAll();
        long engineMs = parseMillis(logs, "Engine total");
        long solverMs = parseMillis(logs, "Solved in");
        long mappingMs = parseMillis(logs, "Prepared planning model in");
        long applyMs = parseMillis(logs, "Applied solver result in");
        long persistenceApprox = Math.max(0L, apiMs - engineMs);
        recordGenerateMetric(engineName(), apiMs, engineMs, solverMs, mappingMs, applyMs,
            persistenceApprox, data);
    }

    @Test
    void regenerateUnlocked_tt1_preservesLockedEntries() throws Exception {
        JsonNode data = postGenerateExpectSuccess(TT1_DEPARTMENT_ID, TT1_SECTION_ID, TT1_SEMESTER, SESSION);
        assertEqualsEntries(expectedTt1Entries(), data);

        // Lock a few non-lab entries before the partial regeneration.
        Timetable timetable = timetableRepository.findById(data.get("id").asLong()).orElseThrow();
        List<TimetableEntry> locked = timetable.getEntries().stream()
            .filter(e -> !Boolean.TRUE.equals(e.getIsLab()))
            .limit(3)
            .toList();
        assertFalse(locked.isEmpty());
        locked.forEach(e -> e.setIsLocked(true));
        entryRepository.saveAll(locked);
        entryRepository.flush();

        Map<Long, String[]> placementBefore = new HashMap<>();
        for (TimetableEntry e : locked) {
            placementBefore.put(e.getId(), new String[]{
                e.getDayOfWeek(),
                String.valueOf(e.getTimeSlot().getId()),
                String.valueOf(e.getClassroom().getId()),
                String.valueOf(e.getSubject().getId()),
                String.valueOf(e.getFaculty().getId())
            });
        }

        long apiStart = System.nanoTime();
        MvcResultHolder result = performRegenerate(data.get("id").asLong());
        long apiMs = Duration.ofNanos(System.nanoTime() - apiStart).toMillis();

        JsonNode regen = result.data();
        assertEquals("GENERATED", regen.get("status").asText());
        assertDurationUnder("API regenerate-unlocked", apiMs, 30_000);

        // Locked entries survive untouched (pinned by @PlanningPin).
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode e : regen.get("entries")) {
            byId.put(e.get("id").asText(), e);
        }
        for (Map.Entry<Long, String[]> before : placementBefore.entrySet()) {
            JsonNode e = byId.get(String.valueOf(before.getKey()));
            assertNotNull(e, "locked entry " + before.getKey() + " must still be present");
            String[] placement = before.getValue();
            assertEquals(placement[0], e.get("dayOfWeek").asText(), "locked entry day must be preserved");
            assertEquals(placement[1], e.get("timeSlotId").asText(), "locked entry slot must be preserved");
            assertEquals(placement[2], e.get("classroomId").asText(), "locked entry room must be preserved");
            assertEquals(placement[3], e.get("subjectId").asText(), "locked entry subject must be preserved");
            assertEquals(placement[4], e.get("facultyId").asText(), "locked entry faculty must be preserved");
            assertTrue(e.get("isLocked").asBoolean(), "locked entry must remain locked");
        }

        // Demand is backfilled around the locked periods, clash-free.
        assertEqualsEntries(expectedTt1RegenerateEntries(), regen);
        assertNoWindowClashes(regen.get("entries"));
        recordRegenerateMetric(engineName(), apiMs, regen);
    }

    private MvcResultHolder performRegenerate(long timetableId) throws Exception {
        var result = mockMvc.perform(post("/timetable/" + timetableId + "/regenerate-unlocked"))
            .andExpect(status().isOk())
            .andReturn();
        var root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(root.get("success").asBoolean(), "regenerate must return success: " + root);
        assertNotNull(root.get("data"));
        return new MvcResultHolder(root.get("data"));
    }

    private record MvcResultHolder(JsonNode data) {
    }
}
