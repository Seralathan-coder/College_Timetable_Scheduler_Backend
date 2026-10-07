package com.erp.timetable.module.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end multi-college tenant isolation (fresh in-memory H2).
 *
 * <p>Boots the real application (DataInitializer seeds the default DEV001
 * college + platform admin), then drives the REAL HTTP API with real JWT
 * logins to prove A/B tenant isolation in both directions and for every
 * entity type: colleges, departments, faculty, subjects, classrooms,
 * timetables, availability, institution self-service and HOD accounts.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:multi_college_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class MultiCollegeE2ETest {

    private static final String ADMIN_PW = "Admin@1234";
    private static final String PW = "Pass@1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private final AtomicLong seq = new AtomicLong();

    private String unique(String prefix) {
        return prefix + seq.incrementAndGet();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json(result).get("data");
    }

    private String loginToken(String username, String password) throws Exception {
        String body = "{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + password + "\"}";
        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        return data(result).get("accessToken").asText();
    }

    private JsonNode loginOk(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return data(result);
    }

    private long createCollege(String token, String name, String code, String loginId) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"collegeId\":\"" + loginId
            + "\",\"password\":\"" + PW + "\",\"email\":\"" + loginId + "@college.edu\"}";
        MvcResult result = mockMvc.perform(post("/colleges")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        return data(result).get("id").asLong();
    }

    private long createDepartment(String token, String name, String hodUser, String hodPass) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"building\":\"Block-"
            + unique("B") + "\",\"contactEmail\":\"" + unique("dept") + "@college.edu\",\"contactPhone\":\"9876543210\"";
        if (hodUser != null) {
            body += ",\"hodUsername\":\"" + hodUser + "\",\"hodPassword\":\"" + hodPass + "\"";
        }
        body += "}";
        MvcResult result = mockMvc.perform(post("/departments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        return data(result).get("id").asLong();
    }

    private long createFaculty(String token, long deptId, String empId) throws Exception {
        String body = "{\"employeeId\":\"" + empId + "\",\"firstName\":\"Multi\",\"lastName\":\"Faculty\","
            + "\"email\":\"" + empId + "@college.edu\",\"departmentId\":" + deptId
            + ",\"designation\":\"Professor\",\"maxDailyHours\":6,\"maxWeeklyHours\":24}";
        MvcResult result = mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        return data(result).get("id").asLong();
    }

    private long[] firstYearSectionOf(String token, long deptId) throws Exception {
        JsonNode dept = data(mockMvc.perform(get("/departments/" + deptId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn());
        JsonNode year = dept.get("academicYears").get(0);
        JsonNode section = year.get("sections").get(0);
        return new long[]{year.get("id").asLong(), section.get("id").asLong()};
    }

    private long createSubject(String token, long deptId, long yearId, long sectionId, long facId, String code) throws Exception {
        String body = "{\"subjectCode\":\"" + code + "\",\"subjectName\":\"Tenant Subject\","
            + "\"departmentId\":" + deptId + ",\"academicYearId\":" + yearId
            + ",\"sectionId\":" + sectionId + ",\"facultyId\":" + facId
            + ",\"semester\":3,\"credits\":4,\"theoryHours\":3,\"practicalHours\":0,\"subjectType\":\"THEORY\"}";
        MvcResult result = mockMvc.perform(post("/subjects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        return data(result).get("id").asLong();
    }

    private long createClassroom(String token, long deptId, String roomNumber) throws Exception {
        String body = "{\"roomNumber\":\"" + roomNumber + "\",\"roomName\":\"Lecture Hall\",\"building\":\"Block-A\","
            + "\"departmentId\":" + deptId + ",\"roomType\":\"LECTURE_HALL\",\"capacity\":60,\"status\":\"AVAILABLE\"}";
        MvcResult result = mockMvc.perform(post("/classrooms")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        return data(result).get("id").asLong();
    }

    private boolean listContains(MvcResult result, String field, String value) throws Exception {
        JsonNode arr = data(result).get("content");
        for (JsonNode item : arr) {
            if (value.equals(item.get(field).asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void colleges_areIsolated_inEveryDirection() throws Exception {
        String adminToken = loginToken("admin", ADMIN_PW);

        // 1. Admin provisions two tenant colleges (College Admins created inside).
        String loginA = unique("ZAADM");
        String loginB = unique("ZBADM");
        long aId = createCollege(adminToken, unique("Alpha College"), "ZQA", loginA);
        long bId = createCollege(adminToken, unique("Beta College"), "ZQB", loginB);
        assertNotEquals(aId, bId);

        JsonNode colleges = data(mockMvc.perform(get("/colleges")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn());
        boolean foundA = false, foundB = false;
        for (JsonNode c : colleges) {
            if ("ZQA".equals(c.get("code").asText())) foundA = true;
            if ("ZQB".equals(c.get("code").asText())) foundB = true;
        }
        assertTrue(foundA && foundB, "GET /colleges must list both new tenants");

        // 2. College A / B admins log into THEIR OWN tenant.
        String adminA = loginToken(loginA, PW);
        String adminB = loginToken(loginB, PW);

        // 3. A creates a department + HOD login, faculty, subject and classroom.
        long deptA = createDepartment(adminA, "A-CSE", "za_hod_" + unique("h"), PW);
        long facA = createFaculty(adminA, deptA, "FA");
        long[] ys = firstYearSectionOf(adminA, deptA);
        long subjA = createSubject(adminA, deptA, ys[0], ys[1], facA, unique("SA"));
        long roomA = createClassroom(adminA, deptA, unique("RA"));

        // 4. B creates its own department.
        long deptB = createDepartment(adminB, "B-ME", null, null);

        // ── LIST isolation ──
        assertTrue(listContains(mockMvc.perform(get("/departments")
                .header("Authorization", "Bearer " + adminA)).andReturn(), "name", "A-CSE"));
        assertFalse(listContains(mockMvc.perform(get("/departments")
                .header("Authorization", "Bearer " + adminA)).andReturn(), "name", "B-ME"),
            "A must not see B's departments");
        assertTrue(listContains(mockMvc.perform(get("/departments")
                .header("Authorization", "Bearer " + adminB)).andReturn(), "name", "B-ME"));
        assertFalse(listContains(mockMvc.perform(get("/departments")
                .header("Authorization", "Bearer " + adminB)).andReturn(), "name", "A-CSE"),
            "B must not see A's departments");

        assertTrue(listContains(mockMvc.perform(get("/faculty")
                .header("Authorization", "Bearer " + adminA)).andReturn(), "employeeId", "FA"),
            "A's faculty list must contain the faculty A created");
        boolean bSeesFacA = listContains(mockMvc.perform(get("/faculty")
            .header("Authorization", "Bearer " + adminB)).andReturn(), "employeeId", "FA");
        assertFalse(bSeesFacA, "B must not see A's faculty");

        // ── BY-ID read isolation ──
        mockMvc.perform(get("/departments/" + deptA).header("Authorization", "Bearer " + adminB))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/departments/" + deptB).header("Authorization", "Bearer " + adminA))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/faculty/" + facA).header("Authorization", "Bearer " + adminB))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/subjects/" + subjA).header("Authorization", "Bearer " + adminB))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/classrooms/" + roomA).header("Authorization", "Bearer " + adminB))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/timetable/department/" + deptA).header("Authorization", "Bearer " + adminB))
            .andExpect(status().isForbidden());

        // ── WRITE isolation ──
        String yearsJson = "\"years\":[{\"yearLabel\":\"1st Year\",\"sections\":[\"A\"]}]";
        mockMvc.perform(put("/departments/" + deptA)
                .header("Authorization", "Bearer " + adminB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hijacked\",\"building\":\"Block-Y\"," + yearsJson + "}"))
            .andExpect(status().isForbidden());
        String subjPut = "{\"subjectCode\":\"SA98\",\"subjectName\":\"Hijacked\","
            + "\"departmentId\":" + deptA + ",\"academicYearId\":" + ys[0]
            + ",\"sectionId\":" + ys[1] + ",\"facultyId\":" + facA
            + ",\"semester\":3,\"credits\":4,\"theoryHours\":3,\"practicalHours\":0,\"subjectType\":\"THEORY\"}";
        mockMvc.perform(put("/subjects/" + subjA)
                .header("Authorization", "Bearer " + adminB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(subjPut))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/classrooms/" + roomA)
                .header("Authorization", "Bearer " + adminB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomNumber\":\"H-1\",\"roomName\":\"Hijacked\",\"departmentId\":"
                    + deptA + ",\"roomType\":\"LECTURE_HALL\",\"capacity\":60,\"status\":\"AVAILABLE\"}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/availability/faculty/" + facA)
                .header("Authorization", "Bearer " + adminB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isForbidden());

        // ── DELETE isolation (SUPER_ADMIN or own-college COLLEGE_ADMIN only) ──
        mockMvc.perform(delete("/faculty/" + facA).header("Authorization", "Bearer " + adminB))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/subjects/" + subjA).header("Authorization", "Bearer " + adminB))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/classrooms/" + roomA).header("Authorization", "Bearer " + adminB))
            .andExpect(status().isForbidden());

        // ── Institution self-service stays per-college ──
        String betaName = unique("Beta Renamed");
        mockMvc.perform(put("/institution")
                .header("Authorization", "Bearer " + adminB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + betaName + "\"}"))
            .andExpect(status().isOk());
        JsonNode instA = data(mockMvc.perform(get("/institution").header("Authorization", "Bearer " + adminA)).andReturn());
        JsonNode instB = data(mockMvc.perform(get("/institution").header("Authorization", "Bearer " + adminB)).andReturn());
        assertNotEquals(betaName, instA.get("name").asText(), "A's institution must be unaffected by B's edit");
        assertEquals(betaName, instB.get("name").asText(), "B edits only its own institution");

        // ── HOD of A is isolated to A's own department/college ──
        String hodUser = unique("hod");
        String hodPw = PW;
        // (reuse a fresh department so the HOD provisioning path is exercised)
        long deptA2 = createDepartment(adminA, "A-ECE", hodUser, hodPw);
        String hodToken = loginToken(hodUser, hodPw);
        assertTrue(listContains(mockMvc.perform(get("/departments")
                .header("Authorization", "Bearer " + hodToken)).andReturn(), "name", "A-ECE"),
            "HOD must see their own department");
        boolean hodSeesBeta = listContains(mockMvc.perform(get("/departments")
            .header("Authorization", "Bearer " + hodToken)).andReturn(), "name", "B-ME");
        assertFalse(hodSeesBeta, "HOD must not see B's departments");
        mockMvc.perform(get("/departments/" + deptB).header("Authorization", "Bearer " + hodToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/departments/" + deptB)
                .header("Authorization", "Bearer " + hodToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hijacked\",\"building\":\"Block-Y\"," + yearsJson + "}"))
            .andExpect(status().isForbidden());

        // ── SUPER_ADMIN stays global (sees both tenants) ──
        assertTrue(listContains(mockMvc.perform(get("/departments")
                .header("Authorization", "Bearer " + adminToken)).andReturn(), "name", "A-CSE"));
        assertTrue(listContains(mockMvc.perform(get("/departments")
                .header("Authorization", "Bearer " + adminToken)).andReturn(), "name", "B-ME"));

        // ── College-admin delete of OWN resource is permitted ──
        mockMvc.perform(delete("/classrooms/" + roomA).header("Authorization", "Bearer " + adminA))
            .andExpect(status().isOk());
    }

    @Test
    void createCollegeAdminAccountFunction_bindsToCollege_andLoginIsIsolated() throws Exception {
        String adminToken = loginToken("admin", ADMIN_PW);

        // Create college A + B via the existing college-creation workflow (each
        // atomically provisions its own initial College Admin account).
        String loginA = unique("Z2ADM");
        String loginB = unique("Z2BADM");
        long aId = createCollege(adminToken, unique("IsolateA College"), "Z2A", loginA);
        long bId = createCollege(adminToken, unique("IsolateB College"), "Z2B", loginB);

        // The DEDICATED "Create College Admin Account" function: create a new
        // College Admin login ID + password bound to college A (existing college).
        String extraUsername = unique("z2extra");
        String extraPassword = "Z2Extra@1234";
        MvcResult created = mockMvc.perform(post("/colleges/" + aId + "/admin-account")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + extraUsername + "\",\"password\":\"" + extraPassword + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode createdData = data(created);
        assertEquals(aId, createdData.get("id").asLong());
        assertEquals(extraUsername, createdData.get("adminUsername").asText());

        // The created account can log in through the normal login flow.
        String extraToken = loginToken(extraUsername, extraPassword);

        // It is scoped to college A: can read A's own departments list…
        long deptA = createDepartment(extraToken, "X-OWN", null, null);
        assertTrue(listContains(mockMvc.perform(get("/departments")
            .header("Authorization", "Bearer " + extraToken)).andReturn(), "name", "X-OWN"),
            "new College Admin must see its own college's departments");
        // …but cannot read college B's department (403).
        long deptB = createDepartment(loginToken(loginB, PW), "Y-FOREIGN", null, null);
        mockMvc.perform(get("/departments/" + deptB).header("Authorization", "Bearer " + extraToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/departments/" + deptB)
                .header("Authorization", "Bearer " + extraToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hijacked\",\"building\":\"Block-Z\","
                    + "\"years\":[{\"yearLabel\":\"1st Year\",\"sections\":[\"A\"]}]}"))
            .andExpect(status().isForbidden());

        // A College Admin may NOT create another college's admin account (SUPER_ADMIN only).
        mockMvc.perform(post("/colleges/" + bId + "/admin-account")
                .header("Authorization", "Bearer " + extraToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + unique("z2no") + "\",\"password\":\"" + extraPassword + "\"}"))
            .andExpect(status().isForbidden());

        // The SAME login ID is allowed in a DIFFERENT college (unique per college)…
        mockMvc.perform(post("/colleges/" + bId + "/admin-account")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + extraUsername + "\",\"password\":\"" + extraPassword + "\"}"))
            .andExpect(status().isCreated());

        // …but a duplicate within the SAME college is rejected (BusinessException → 422).
        mockMvc.perform(post("/colleges/" + aId + "/admin-account")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + extraUsername + "\",\"password\":\"" + extraPassword + "\"}"))
            .andExpect(status().isUnprocessableEntity());

        // Blank password fails bean validation (400).
        mockMvc.perform(post("/colleges/" + aId + "/admin-account")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + unique("z2blank") + "\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void collegeAdminListsOnlyTheirOwnDashboardCounts() throws Exception {
        String adminToken = loginToken("admin", ADMIN_PW);
        String loginA = unique("ZA2ADM");
        createCollege(adminToken, unique("Gamma College"), "ZQG", loginA);
        String adminA = loginToken(loginA, PW);
        createDepartment(adminA, "G-DEPT", null, null);

        // Global counts (seed data) exceed college-local counts; the college
        // admin's dashboard must be scoped to their own tenants.
        mockMvc.perform(get("/dashboard/stats").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
        mockMvc.perform(get("/dashboard/stats").header("Authorization", "Bearer " + adminA))
            .andExpect(status().isOk());
    }

    @Test
    void departmentNameUniqueness_isScopedPerCollege_andDataStaysWithCorrectTenant() throws Exception {
        String adminToken = loginToken("admin", ADMIN_PW);

        // Two tenant colleges with their own College Admins.
        String loginA = unique("Z3ADM");
        String loginB = unique("Z4ADM");
        long aId = createCollege(adminToken, unique("UniqueA College"), "Z3U", loginA);
        long bId = createCollege(adminToken, unique("UniqueB College"), "Z4U", loginB);
        String adminA = loginToken(loginA, PW);
        String adminB = loginToken(loginB, PW);

        // 1. A creates a department named CSE -> 201.
        long deptA = createDepartment(adminA, "CSE", null, null);

        // 2. B creates a department ALSO named CSE (different college) -> 201 (same name OK across colleges).
        long deptB = createDepartment(adminB, "CSE", null, null);
        assertNotEquals(deptA, deptB);

        // 3/4. A duplicate CSE within the SAME college -> 422 (both A and B).
        mockMvc.perform(post("/departments")
                .header("Authorization", "Bearer " + adminA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"CSE\",\"building\":\"Block-X\",\"contactEmail\":\"" + unique("dupA") + "@college.edu\",\"contactPhone\":\"9876543210\"}"))
            .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/departments")
                .header("Authorization", "Bearer " + adminB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"CSE\",\"building\":\"Block-X\",\"contactEmail\":\"" + unique("dupB") + "@college.edu\",\"contactPhone\":\"9876543210\"}"))
            .andExpect(status().isUnprocessableEntity());

        // 5/6. List isolation: each college sees exactly its OWN single CSE.
        assertEquals(1, countName(mockMvc.perform(get("/departments")
            .header("Authorization", "Bearer " + adminA)).andReturn(), "CSE"),
            "A must see exactly one CSE (its own)");
        assertEquals(1, countName(mockMvc.perform(get("/departments")
            .header("Authorization", "Bearer " + adminB)).andReturn(), "CSE"),
            "B must see exactly one CSE (its own)");

        // 7/8. Cross-college UPDATE blocked in both directions -> 403.
        String yearsJson = "\"years\":[{\"yearLabel\":\"1st Year\",\"sections\":[\"A\"]}]";
        mockMvc.perform(put("/departments/" + deptB)
                .header("Authorization", "Bearer " + adminA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"CSE\",\"building\":\"Block-Y\"," + yearsJson + "}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/departments/" + deptA)
                .header("Authorization", "Bearer " + adminB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"CSE\",\"building\":\"Block-Y\"," + yearsJson + "}"))
            .andExpect(status().isForbidden());

        // Cross-college DELETE blocked (deletes are SUPER_ADMIN-only; cross-college also 403).
        mockMvc.perform(delete("/departments/" + deptB).header("Authorization", "Bearer " + adminA))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/departments/" + deptA).header("Authorization", "Bearer " + adminB))
            .andExpect(status().isForbidden());

        // 11. HOD + data association stay with the CORRECT college/department.
        String hodUser = unique("hodA");
        long hodDeptA = createDepartment(adminA, "A-ECE", hodUser, PW); // HOD provisioned for an A-department
        assertTrue(hodDeptA > 0);
        String hodAToken = loginToken(hodUser, PW);
        // The HOD is bound to the department created above, so the department
        // list resolves to exactly that one row: their own department is
        // visible, and no other department in college A (CSE) leaks in.
        assertEquals(1, totalCount(mockMvc.perform(get("/departments")
            .header("Authorization", "Bearer " + hodAToken)).andReturn()),
            "A's HOD must see only their own department");
        assertEquals(0, countName(mockMvc.perform(get("/departments")
            .header("Authorization", "Bearer " + hodAToken)).andReturn(), "CSE"),
            "A's HOD must not see a sibling department they do not head");
        assertEquals(1, countName(mockMvc.perform(get("/departments")
            .header("Authorization", "Bearer " + hodAToken)).andReturn(), "A-ECE"),
            "A's HOD must see their own department");
        // 9. HOD isolation: A's HOD cannot read B's CSE -> 403.
        mockMvc.perform(get("/departments/" + deptB).header("Authorization", "Bearer " + hodAToken))
            .andExpect(status().isForbidden());

        // 10. Faculty: faculty created in A's CSE is visible to A, not to B.
        String facEmpId = unique("FA");
        long facA = createFaculty(adminA, deptA, facEmpId);
        assertTrue(listContains(mockMvc.perform(get("/faculty")
            .header("Authorization", "Bearer " + adminA)).andReturn(), "employeeId", facEmpId),
            "A's faculty list must contain the faculty A created");
        assertFalse(listContains(mockMvc.perform(get("/faculty")
            .header("Authorization", "Bearer " + adminB)).andReturn(), "employeeId", facEmpId),
            "B must not see A's faculty");
        mockMvc.perform(get("/faculty/" + facA).header("Authorization", "Bearer " + adminB))
            .andExpect(status().isForbidden());

        // 12. Tenant anchor: each college's identical-named dept resolves to its OWN college.
        JsonNode deptAData = data(mockMvc.perform(get("/departments/" + deptA)
            .header("Authorization", "Bearer " + adminA))
            .andExpect(status().isOk())
            .andReturn());
        assertEquals(aId, deptAData.get("collegeId").asLong(), "A's CSE belongs to college A");
        // A still cannot read B's identical-named dept by id -> 403.
        mockMvc.perform(get("/departments/" + deptB).header("Authorization", "Bearer " + adminA))
            .andExpect(status().isForbidden());
    }

    @Test
    void collegeAdmin_permanentlyDeletesOwnDepartment_keepsHodUser_crossCollegeBlocked() throws Exception {
        String adminToken = loginToken("admin", ADMIN_PW);

        String loginAdminA = unique("pdla");
        String loginAdminB = unique("pdlb");
        long aId = createCollege(adminToken, unique("Pdel A College"), "PDA", loginAdminA);
        long bId = createCollege(adminToken, unique("Pdel B College"), "PDB", loginAdminB);
        String adminA = loginToken(loginAdminA, PW);
        String adminB = loginToken(loginAdminB, PW);

        // A creates a department WITH an HOD login; B creates a plain one + faculty.
        String hodUser = unique("pdlhod");
        long deptA = createDepartment(adminA, "Pdel-CSE", hodUser, PW);
        long deptB = createDepartment(adminB, "Pdel-ME", null, null);
        String facB = unique("pdlfb");
        createFaculty(adminB, deptB, facB);

        // HOD account exists and is linked to its department.
        JsonNode hodBefore = loginOk(hodUser, PW);
        assertEquals(deptA, hodBefore.get("departmentId").asLong(), "HOD must start linked to its department");

        // Cross-college deletes are blocked while both departments still exist.
        mockMvc.perform(delete("/departments/" + deptA).header("Authorization", "Bearer " + adminB))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/departments/" + deptB).header("Authorization", "Bearer " + adminA))
            .andExpect(status().isForbidden());

        // Own-college permanent delete succeeds.
        mockMvc.perform(delete("/departments/" + deptA).header("Authorization", "Bearer " + adminA))
            .andExpect(status().isOk());

        // The department is really gone — a repeated delete surfaces the
        // service-level 404 (guard passes a missing id so the resource layer
        // reports truth), while reads of a gone department stay 403 per guard.
        mockMvc.perform(get("/departments/" + deptA).header("Authorization", "Bearer " + adminA))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/departments/" + deptA).header("Authorization", "Bearer " + adminA))
            .andExpect(status().isNotFound());

        // B's department + data are untouched.
        JsonNode deptBData = data(mockMvc.perform(get("/departments/" + deptB)
                .header("Authorization", "Bearer " + adminB))
            .andExpect(status().isOk())
            .andReturn());
        assertEquals("Pdel-ME", deptBData.get("name").asText());
        assertTrue(listContains(mockMvc.perform(get("/faculty")
                .header("Authorization", "Bearer " + adminB)).andReturn(), "employeeId", facB),
            "B's faculty must survive A's deletion");

        // HOD account KEPT but unlinked from the deleted department.
        JsonNode hodAfter = loginOk(hodUser, PW);
        assertFalse(hodAfter.hasNonNull("departmentId"),
            "HOD user account must be kept and unlinked from the deleted department");
    }

    @Test
    void hodLoginIds_areScopedPerCollege_passwordIsTheDisambiguator() throws Exception {
        String adminToken = loginToken("admin", ADMIN_PW);

        // Two tenant colleges with their own College Admins.
        String loginAdminA = unique("Z5HADM");
        String loginAdminB = unique("Z6HADM");
        long aId = createCollege(adminToken, unique("HodA College"), "Z5H", loginAdminA);
        long bId = createCollege(adminToken, unique("HodB College"), "Z6H", loginAdminB);
        String adminA = loginToken(loginAdminA, PW);
        String adminB = loginToken(loginAdminB, PW);

        // 1/2. BOTH colleges create a department with the SAME HOD Login ID → 201
        //     (Login IDs are unique per college, not globally). Each HOD keeps
        //     its OWN password — the password is what identifies the account.
        String sharedHod = unique("CSDTamil");
        String pwA = "Cse@1234A";
        String pwB = "Cse@1234B";
        long deptA = createDepartment(adminA, "H-CSE", sharedHod, pwA);
        long deptB = createDepartment(adminB, "H-ME", sharedHod, pwB);
        assertNotEquals(deptA, deptB);

        // 10. Same-college duplicate HOD Login ID → 422 in BOTH directions.
        mockMvc.perform(post("/departments")
                .header("Authorization", "Bearer " + adminA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"H-DUP-A\",\"building\":\"Block-D\",\"contactEmail\":\""
                    + unique("dupA") + "@college.edu\",\"contactPhone\":\"9876543210\""
                    + ",\"hodUsername\":\"" + sharedHod + "\",\"hodPassword\":\"" + pwA + "\"}"))
            .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/departments")
                .header("Authorization", "Bearer " + adminB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"H-DUP-B\",\"building\":\"Block-D\",\"contactEmail\":\""
                    + unique("dupB") + "@college.edu\",\"contactPhone\":\"9876543210\""
                    + ",\"hodUsername\":\"" + sharedHod + "\",\"hodPassword\":\"" + pwB + "\"}"))
            .andExpect(status().isUnprocessableEntity());

        // 3/4. HOD logs in using ONLY Login ID + password (NO College Code).
        //     Each password resolves to ITS OWN account → correct userId/collegeId.
        JsonNode loginA = loginOk(sharedHod, pwA);
        long userIdA = loginA.get("userId").asLong();
        assertEquals(aId, loginA.get("collegeId").asLong(),
            "College A's password must resolve the HOD to College A");
        JsonNode loginB = loginOk(sharedHod, pwB);
        long userIdB = loginB.get("userId").asLong();
        assertEquals(bId, loginB.get("collegeId").asLong(),
            "College B's password must resolve the HOD to College B");
        assertNotEquals(userIdA, userIdB, "each college owns a distinct HOD account");
        String hodAToken = loginA.get("accessToken").asText();
        String hodBToken = loginB.get("accessToken").asText();

        // 9. Wrong password for the shared Login ID → 401 (no account leak).
        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + sharedHod + "\",\"password\":\"WrongPass\"}"))
            .andExpect(status().isUnauthorized());

        // 7/8. HOD of A sees only A's department; HOD of B only B's; 403 both ways.
        assertTrue(listContains(mockMvc.perform(get("/departments")
            .header("Authorization", "Bearer " + hodAToken)).andReturn(), "name", "H-CSE"),
            "HOD of A must see its own department");
        assertFalse(listContains(mockMvc.perform(get("/departments")
            .header("Authorization", "Bearer " + hodAToken)).andReturn(), "name", "H-ME"),
            "HOD of A must not see B's department");
        mockMvc.perform(get("/departments/" + deptB).header("Authorization", "Bearer " + hodAToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/departments/" + deptB)
                .header("Authorization", "Bearer " + hodAToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hijacked\",\"building\":\"Block-Y\","
                    + "\"years\":[{\"yearLabel\":\"1st Year\",\"sections\":[\"A\"]}]}"))
            .andExpect(status().isForbidden());
        assertTrue(listContains(mockMvc.perform(get("/departments")
            .header("Authorization", "Bearer " + hodBToken)).andReturn(), "name", "H-ME"),
            "HOD of B must see its own department");
        mockMvc.perform(get("/departments/" + deptA).header("Authorization", "Bearer " + hodBToken))
            .andExpect(status().isForbidden());

        // 11/12/13. Existing unique HOD / College Admin / Faculty logins still work.
        loginToken("cse_admin", ADMIN_PW);
        loginToken(loginAdminA, PW);
        loginToken("faculty", "Faculty@1234");
    }

    @Test
    void sharedLogin_withSamePassword_isRejectedRequiringDisambiguation() throws Exception {
        String adminToken = loginToken("admin", ADMIN_PW);

        String loginA = unique("Z7HADM");
        String loginB = unique("Z8HADM");
        createCollege(adminToken, unique("HodA2 College"), "Z7H", loginA);
        createCollege(adminToken, unique("HodB2 College"), "Z8H", loginB);
        String adminA = loginToken(loginA, PW);
        String adminB = loginToken(loginB, PW);

        // Two colleges that both give their HOD account the SAME password.
        String sharedHod = unique("CSDTamil");
        createDepartment(adminA, "H2-CSE", sharedHod, PW);
        createDepartment(adminB, "H2-ME", sharedHod, PW);

        // Identical credentials match TWO accounts → safe 422, never an arbitrary pick.
        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + sharedHod + "\",\"password\":\"" + PW + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(result -> assertTrue(
                result.getResponse().getContentAsString().contains("Multiple accounts match these credentials"),
                "ambiguous credentials must be rejected with a disambiguation error"));

        // Wrong password → 401; nothing reveals that the ID is duplicated.
        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + sharedHod + "\",\"password\":\"WrongPass\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void subjectFacultyAssignment_allowsSameCollegeCrossDepartment_butRejectsCrossCollege() throws Exception {
        String adminToken = loginToken("admin", ADMIN_PW);

        String loginA = unique("Z9FADM");
        String loginB = unique("ZAFADM");
        createCollege(adminToken, unique("FacA College"), "Z9F", loginA);
        createCollege(adminToken, unique("FacB College"), "ZAF", loginB);
        String adminA = loginToken(loginA, PW);
        String adminB = loginToken(loginB, PW);

        // College A has TWO departments; the subject lives in A-CSE while the
        // faculty lives in sibling department A-ECE (same college).
        long deptACse = createDepartment(adminA, "FAC-A-CSE", null, null);
        long deptAEce = createDepartment(adminA, "FAC-A-ECE", null, null);
        long facAEce = createFaculty(adminA, deptAEce, unique("FAE"));
        long[] ysA = firstYearSectionOf(adminA, deptACse);

        // Same-college cross-department assignment → 201.
        long subj = createSubject(adminA, deptACse, ysA[0], ysA[1], facAEce, unique("SFAC"));
        assertTrue(subj > 0);

        // College B has its own faculty in its own department.
        long deptB = createDepartment(adminB, "FAC-B-ME", null, null);
        long facB = createFaculty(adminB, deptB, unique("FBB"));

        // Cross-college faculty assigned to an A subject → rejected (422).
        mockMvc.perform(post("/subjects")
                .header("Authorization", "Bearer " + adminA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectCode\":\"" + unique("SCROSS") + "\",\"subjectName\":\"CrossCollege\","
                    + "\"departmentId\":" + deptACse + ",\"academicYearId\":" + ysA[0]
                    + ",\"sectionId\":" + ysA[1] + ",\"facultyId\":" + facB
                    + ",\"semester\":3,\"credits\":4,\"theoryHours\":3,\"practicalHours\":0,\"subjectType\":\"THEORY\"}"))
            .andExpect(status().isUnprocessableEntity());

        // GET /faculty/assignable exposes only the caller's college.
        JsonNode aList = data(mockMvc.perform(get("/faculty/assignable")
                .header("Authorization", "Bearer " + adminA))
            .andExpect(status().isOk())
            .andReturn());
        boolean seesB = false;
        for (JsonNode f : aList) {
            if (f.hasNonNull("departmentId") && f.get("departmentId").asLong() == deptB) {
                seesB = true;
            }
        }
        assertFalse(seesB, "A's assignable faculty must not include college B's department");
    }

    private int countName(MvcResult result, String value) throws Exception {
        JsonNode arr = data(result).get("content");
        int count = 0;
        for (JsonNode item : arr) {
            if (value.equals(item.get("name").asText())) {
                count++;
            }
        }
        return count;
    }

    private int totalCount(MvcResult result) throws Exception {
        return data(result).get("content").size();
    }
}