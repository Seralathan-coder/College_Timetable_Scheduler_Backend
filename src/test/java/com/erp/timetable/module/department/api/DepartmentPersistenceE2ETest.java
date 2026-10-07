package com.erp.timetable.module.department.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 11 — Department persistence E2E regression test.
 *
 * <p>Boots the real application (MVC + security + JPA) on the in-memory H2
 * profile and drives the exact REST contract the frontend {@code DepartmentsPage}
 * uses ({@code POST /api/v1/departments} then {@code GET /api/v1/departments}).
 * It guards the full create → cascade → DB → readback → list (dropdown
 * selectability) chain that a mocked unit test cannot verify: the department,
 * its four academic years and its per-year sections must all be written to the
 * database as real rows and come back with their generated IDs.
 *
 * <p>The test transaction rolls everything back; nothing is left in the DB.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@WithMockUser(roles = "SUPER_ADMIN")
@Transactional
class DepartmentPersistenceE2ETest {

    private static final String DEPT_NAME = "Verification Department";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Byte-for-byte the payload shape the frontend sends (all four years, A–B sections). */
    private String frontendPayload() {
        return "{"
            + "\"name\":\"" + DEPT_NAME + "\","
            + "\"hodName\":\"VDEPT\","
            + "\"contactEmail\":\"vdept@college.edu\","
            + "\"contactPhone\":\"+91 9000000002\","
            + "\"building\":\"VDEPT Block\","
            + "\"description\":\"Temporary persistence-verification department\","
            + "\"years\":["
            + "{\"yearLabel\":\"1st Year\",\"enabled\":true,\"sections\":[\"A\",\"B\"]},"
            + "{\"yearLabel\":\"2nd Year\",\"enabled\":true,\"sections\":[\"A\",\"B\"]},"
            + "{\"yearLabel\":\"3rd Year\",\"enabled\":true,\"sections\":[\"A\",\"B\"]},"
            + "{\"yearLabel\":\"4th Year\",\"enabled\":true,\"sections\":[\"A\",\"B\"]}"
            + "]}";
    }

    @Test
    void createDepartment_persistsYearsAndSectionsAndReadsBack() throws Exception {
        // ── 1. Create exactly as the UI does ───────────────────────────
        MvcResult createResult = mockMvc.perform(post("/departments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(frontendPayload()))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode create = objectMapper.readTree(createResult.getResponse().getContentAsString());
        assertTrue(create.get("success").asBoolean(), "envelope success: " + create);
        assertEquals("Department created successfully", create.get("message").asText());

        JsonNode dept = create.get("data");
        assertNotNull(dept.get("id"), "created department must carry a generated id");
        long deptId = dept.get("id").asLong();
        assertEquals(DEPT_NAME, dept.get("name").asText());
        assertEquals("VDEPT", dept.get("hodName").asText());
        assertTrue(dept.get("isArchived").isBoolean());
        JsonNode years = dept.get("academicYears");
        assertNotNull(years, "response must include academicYears");
        assertEquals(4, years.size(), "every department must have four academic years");

        for (JsonNode year : years) {
            assertNotNull(year.get("id"), "each year must be persisted with its own id");
            assertTrue(year.get("isEnabled").asBoolean());
            assertEquals(2, year.get("sections").size());
            for (JsonNode section : year.get("sections")) {
                assertNotNull(section.get("id"), "each section must be persisted with its own id");
                assertEquals(60, section.get("studentStrength").asInt());
                assertEquals("ACTIVE", section.get("status").asText());
            }
        }

        // ── 2. DB ground truth — rows really exist, not just in memory ──
        Integer deptRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM departments WHERE id = ? AND name = ?", Integer.class, deptId, DEPT_NAME);
        assertEquals(1, deptRows);

        Integer yearRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM academic_years WHERE department_id = ?", Integer.class, deptId);
        assertEquals(4, yearRows);

        Integer sectionRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sections WHERE academic_year_id IN (SELECT id FROM academic_years WHERE department_id = ?)",
            Integer.class, deptId);
        assertEquals(8, sectionRows);

        // ── 3. Read back by id (service + repository round trip) ────────
        MvcResult getResult = mockMvc.perform(get("/departments/" + deptId))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode getDept = objectMapper.readTree(getResult.getResponse().getContentAsString()).get("data");
        assertEquals(DEPT_NAME, getDept.get("name").asText());
        assertEquals(4, getDept.get("academicYears").size());
        JsonNode firstYear = getDept.get("academicYears").get(0);
        assertEquals("1st Year", firstYear.get("yearLabel").asText());
        assertEquals(2, firstYear.get("sections").size());
        assertEquals("A", firstYear.get("sections").get(0).get("name").asText());

        // ── 4. List contract — the department must appear in the list the
        //        Faculty / Subject / Timetable dropdowns are built from ─────
        MvcResult listResult = mockMvc.perform(get("/departments")
                .param("search", "Verification")
                .param("isArchived", "false")
                .param("size", "50"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode content = objectMapper.readTree(listResult.getResponse().getContentAsString())
            .get("data").get("content");
        JsonNode listed = null;
        for (JsonNode d : content) {
            if (DEPT_NAME.equals(d.get("name").asText())) {
                listed = d;
                break;
            }
        }
        assertNotNull(listed, "created department must appear in GET /departments list (dropdown source)");
        assertEquals(4, listed.get("academicYears").size(), "listed department must retain its persisted years");
        assertEquals(deptId, listed.get("id").asLong());
    }
}
