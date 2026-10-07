package com.erp.timetable.module.timetable.api;

import com.erp.timetable.module.timetable.engine.ScheduleEngine;
import com.erp.timetable.module.timetable.repository.TimetableEntryRepository;
import com.erp.timetable.module.timetable.repository.TimetableRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 — API-level end-to-end verification harness.
 *
 * <p>Boots the full application (MVC + security + JPA + the selected scheduling
 * engine) on an in-memory H2 database and drives the real REST contract
 * ({@code POST /api/v1/timetable/generate} and
 * {@code POST /api/v1/timetable/{id}/regenerate-unlocked}) exactly as the
 * frontend does, authenticating as a HOD via {@code @WithMockUser}.
 *
 * <p>Concrete subclasses pick the engine ({@code timetable.scheduler.engine})
 * and a dedicated database name so Greedy and Timefold runs are fully isolated
 * and comparable on the same TT1 dataset. The TT1 dataset is loaded from
 * {@code dataset/tt1-dump.sql} (the real exported dataset) unless a subclass
 * overrides {@link #loadDataset()} to keep the seeded master data.
 *
 * <p>Nothing here modifies the frontend, the REST contracts, the Greedy engine
 * or the default engine selection. The test transaction rolls everything back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@WithMockUser(roles = "HOD")
@Transactional
@ExtendWith(OutputCaptureExtension.class)
public abstract class AbstractTimetableApiE2E {

    /** TT1 dataset facts (see {@code dataset/tt1-dump.sql}). */
    protected static final long TT1_DEPARTMENT_ID = 4L;   // CSD
    protected static final long TT1_SECTION_ID = 25L;     // section A (strength 60)
    protected static final int TT1_SEMESTER = 1;
    protected static final int TT1_THEORY_DEMAND = 36;    // theory periods
    protected static final int TT1_TOTAL_DEMAND = 42;     // 36 theory + 6 practical periods (CS471/CS785/CS691 × 2)
    protected static final int TT1_WINDOWS = 42;          // 7 teaching slots (1-4,6-8) x 6 days

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected TimetableRepository timetableRepository;

    @Autowired
    protected TimetableEntryRepository entryRepository;

    @Autowired
    protected ScheduleEngine engine;

    @BeforeEach
    void loadDatasetForTest() {
        loadDataset();
    }

    /**
     * Loads the real TT1 dataset. Subclasses that need the seeded master data
     * (feasible-scenario tests) override this with a no-op.
     */
    protected void loadDataset() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("RUNSCRIPT FROM 'classpath:dataset/tt1-dump.sql'");
    }

    /** Engine under test, used for the comparison metrics. */
    protected abstract String engineName();

    /** The number of entries the engine is expected to produce on TT1. */
    protected abstract int expectedTt1Entries();

    /** Entries expected after {@code regenerate-unlocked} on TT1 (engines may backfill differently). */
    protected int expectedTt1RegenerateEntries() {
        return expectedTt1Entries();
    }

    // ── REST contract helpers ─────────────────────────────────────────────

    protected JsonNode postGenerateExpectSuccess(long departmentId, long sectionId,
            int semester, String academicSession) throws Exception {
        String body = "{"
            + "\"departmentId\":" + departmentId
            + ",\"sectionId\":" + sectionId
            + ",\"semester\":" + semester
            + ",\"academicSession\":\"" + academicSession + "\"}";

        MvcResult result = mockMvc.perform(post("/timetable/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(root.get("success").asBoolean(), "generate must return success: " + root);
        assertNotNull(root.get("message"), "envelope must carry a message");
        assertNotNull(root.get("timestamp"), "envelope must carry a timestamp");
        JsonNode data = root.get("data");
        assertNotNull(data, "envelope must carry a data payload");
        assertTimetableShape(data);
        return data;
    }

    protected void assertTimetableShape(JsonNode d) {
        assertNotNull(d.get("id"));
        assertNotNull(d.get("academicSession"));
        assertNotNull(d.get("departmentId"));
        assertNotNull(d.get("departmentName"));
        assertNotNull(d.get("sectionId"));
        assertNotNull(d.get("sectionName"));
        assertNotNull(d.get("semester"));
        assertNotNull(d.get("status"));
        assertNotNull(d.get("conflictCount"));
        assertNotNull(d.get("optimizationScore"));
        assertTrue(d.has("entries"), "response must expose entries");
        assertTrue(d.has("conflicts"), "response must expose conflicts");
    }

    protected void assertEntryShape(JsonNode e) {
        assertNotNull(e.get("id"));
        assertNotNull(e.get("dayOfWeek"));
        assertNotNull(e.get("timeSlotId"));
        assertNotNull(e.get("timeSlotLabel"));
        assertNotNull(e.get("timeSlotTime"));
        assertNotNull(e.get("subjectId"));
        assertNotNull(e.get("subjectCode"));
        assertNotNull(e.get("subjectName"));
        assertNotNull(e.get("subjectType"));
        assertNotNull(e.get("facultyId"));
        assertNotNull(e.get("facultyName"));
        assertNotNull(e.get("classroomId"));
        assertNotNull(e.get("roomNumber"));
        assertNotNull(e.get("roomName"));
        assertNotNull(e.get("isLocked"));
    }

    /** No two entries may share a (day, slot), faculty window or room window. */
    protected void assertNoWindowClashes(JsonNode entries) {
        Set<String> windows = new HashSet<>();
        Set<String> facultyWindows = new HashSet<>();
        Set<String> roomWindows = new HashSet<>();
        for (JsonNode e : entries) {
            String window = e.get("dayOfWeek").asText() + "|" + e.get("timeSlotId").asText();
            assertTrue(windows.add(window), "duplicate (day, slot) window: " + window);
            assertTrue(facultyWindows.add(e.get("facultyId").asText() + "|" + window),
                "faculty double-booked at " + window);
            assertTrue(roomWindows.add(e.get("classroomId").asText() + "|" + window),
                "room double-booked at " + window);
        }
    }

    /** Parses the engine's timing logs (present only for the Timefold engine). */
    protected long parseMillis(String output, String label) {
        var matcher = Pattern.compile(Pattern.quote(label) + "[^\\d]*(\\d+)\\s*ms").matcher(output);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    protected void recordGenerateMetric(String engine, long apiMs, long engineMs,
            long solverMs, long mappingMs, long applyMs, long persistenceApprox, JsonNode data) {
        System.out.println("E2E_METRIC|engine=" + engine
            + "|api=" + apiMs + "ms"
            + "|engineTotal=" + engineMs + "ms"
            + "|solver=" + solverMs + "ms"
            + "|mapping=" + mappingMs + "ms"
            + "|apply=" + applyMs + "ms"
            + "|persistenceApprox=" + persistenceApprox + "ms"
            + "|entries=" + data.get("entries").size()
            + "|conflictCount=" + data.get("conflictCount").asInt()
            + "|status=" + data.get("status").asText()
            + "|optimization=" + data.get("optimizationScore").asInt());
    }

    protected void recordRegenerateMetric(String engine, long apiMs, JsonNode data) {
        System.out.println("E2E_METRIC|engine=" + engine
            + "|regenerateUnlocked|api=" + apiMs + "ms"
            + "|entries=" + data.get("entries").size()
            + "|conflictCount=" + data.get("conflictCount").asInt()
            + "|status=" + data.get("status").asText());
    }

    protected void assertDurationUnder(String label, long millis, long capMillis) {
        assertTrue(millis < capMillis,
            label + " must complete well under " + capMillis + " ms, took " + millis + " ms");
    }

    protected void assertEqualsEntries(int expected, JsonNode data) {
        assertEquals(expected, data.get("entries").size(),
            "expected entry count mismatch (engine=" + engineName() + ")");
    }
}
