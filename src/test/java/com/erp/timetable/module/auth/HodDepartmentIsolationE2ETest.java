package com.erp.timetable.module.auth;

import com.erp.timetable.module.auth.entity.Role;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.RoleRepository;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HOD department isolation (fresh in-memory H2, real JWT logins).
 *
 * <p>Proves an HOD is confined to the department recorded in
 * {@code users.department_id} for their own account — resolved from the database,
 * never from a request field — across every list endpoint, the dashboard, the
 * read-only department view, and the parent identifiers inside create/update
 * bodies.
 *
 * <p>Two sibling departments are created in the seeded college, each with its own
 * HOD account, so isolation is proved in both directions. No department name,
 * id, email or role-to-department mapping is hardcoded.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:hod_dept_isolation_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class HodDepartmentIsolationE2ETest {

    private static final String ADMIN_PW = "Admin@1234";
    private static final String HOD_PW = "Hod@12345";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final AtomicLong seq = new AtomicLong();

    private String unique(String prefix) {
        return prefix + seq.incrementAndGet();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private String message(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode m = node.get("message");
        return m == null ? "" : m.asText();
    }

    private String token(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return data(result).get("accessToken").asText();
    }

    private long createDepartment(String adminToken, String name, String hodUser) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"building\":\"" + unique("Blk") + "\","
            + "\"contactEmail\":\"" + unique("dept") + "@college.edu\",\"contactPhone\":\"9876543210\","
            + "\"hodUsername\":\"" + hodUser + "\",\"hodPassword\":\"" + HOD_PW + "\"}";
        return data(mockMvc.perform(post("/departments")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn()).get("id").asLong();
    }

    private long createFaculty(String token, long deptId) throws Exception {
        String emp = unique("EMP");
        return data(mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeId\":\"" + emp + "\",\"firstName\":\"Dept\",\"lastName\":\"Member\","
                    + "\"email\":\"" + emp + "@college.edu\",\"departmentId\":" + deptId
                    + ",\"designation\":\"Professor\",\"maxDailyHours\":6,\"maxWeeklyHours\":24}"))
            .andExpect(status().isCreated())
            .andReturn()).get("id").asLong();
    }

    private long createClassroom(String token, long deptId, String room) throws Exception {
        return data(mockMvc.perform(post("/classrooms")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomNumber\":\"" + room + "\",\"roomName\":\"" + room + "\",\"building\":\"B1\","
                    + "\"departmentId\":" + deptId + ",\"roomType\":\"LECTURE_HALL\",\"capacity\":60,\"floor\":1}"))
            .andExpect(status().isCreated())
            .andReturn()).get("id").asLong();
    }

    private long createSubject(String token, long deptId, long yearId, long sectionId, Long facultyId) throws Exception {
        StringBuilder body = new StringBuilder("{\"subjectCode\":\"" + unique("SUB") + "\",\"subjectName\":\"Scoped\","
            + "\"departmentId\":" + deptId + ",\"academicYearId\":" + yearId
            + ",\"sectionId\":" + sectionId + ",\"semester\":1,\"credits\":3,\"theoryHours\":3,\"practicalHours\":0}");
        if (facultyId != null) {
            body.append(",\"facultyId\":").append(facultyId);
        }
        body.append("}");
        return data(mockMvc.perform(post("/subjects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
            .andExpect(status().isCreated())
            .andReturn()).get("id").asLong();
    }

    /** First enabled academic year + its first section of a department. */
    private long[] yearAndSection(String token, long deptId) throws Exception {
        JsonNode dept = data(mockMvc.perform(get("/departments/" + deptId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn());
        JsonNode year = dept.get("academicYears").get(0);
        return new long[] { year.get("id").asLong(), year.get("sections").get(0).get("id").asLong() };
    }

    private JsonNode listContent(String token, String path) throws Exception {
        return data(mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()).get("content");
    }

    private boolean containsId(JsonNode content, long id) {
        for (JsonNode node : content) {
            if (node.has("id") && node.get("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }

    private int sizeOf(JsonNode content) {
        return content == null ? 0 : content.size();
    }

    /**
     * An HOD-shaped account that is deliberately NOT linked to any department and
     * carries no college either, so nothing about its scope can be inferred from
     * a parent reference. Used to prove an unassigned HOD is denied with an
     * actionable message instead of being silently widened.
     */
    private String createUnassignedHod() {
        String username = "hodfree" + seq.incrementAndGet();
        User user = User.builder()
            .username(username)
            .email(username + "@college.edu")
            .password(passwordEncoder.encode(HOD_PW))
            .fullName(username)
            .isActive(true)
            .build();
        user.addRole(roleRepository.findByName(RoleName.ROLE_HOD).orElseThrow());
        userRepository.save(user);
        return username;
    }

    /** Two departments in the seeded college, each with its own HOD account. */
    private record Fixture(String admin, String hodA, String hodB,
                           long deptA, long deptB,
                           long facA, long facB,
                           long roomA, long roomB,
                           long subA,
                           long yearA, long sectionA,
                           long yearB, long sectionB) {}

    private Fixture fixture() throws Exception {
        String admin = token("admin", ADMIN_PW);
        String hodUserA = "hoda" + seq.incrementAndGet();
        String hodUserB = "hodb" + seq.incrementAndGet();
        long deptA = createDepartment(admin, "HODISO-A-" + seq.incrementAndGet(), hodUserA);
        long deptB = createDepartment(admin, "HODISO-B-" + seq.incrementAndGet(), hodUserB);

        long facA = createFaculty(admin, deptA);
        long facB = createFaculty(admin, deptB);
        long roomA = createClassroom(admin, deptA, "RA-" + seq.incrementAndGet());
        long roomB = createClassroom(admin, deptB, "RB-" + seq.incrementAndGet());

        long[] ya = yearAndSection(admin, deptA);
        long[] yb = yearAndSection(admin, deptB);
        long subA = createSubject(admin, deptA, ya[0], ya[1], facA);

        return new Fixture(admin, hodUserA, hodUserB, deptA, deptB, facA, facB, roomA, roomB,
            subA, ya[0], ya[1], yb[0], yb[1]);
    }

    // ── Lists ───────────────────────────────────────────────────────────

    @Test
    void hodListEndpointsReturnOnlyOwnDepartmentRows() throws Exception {
        Fixture f = fixture();
        String a = token(f.hodA(), HOD_PW);
        String b = token(f.hodB(), HOD_PW);

        for (String token : new String[] { a, b }) {
            JsonNode depts = listContent(token, "/departments?size=100");
            assertEquals(1, sizeOf(depts), "HOD must see exactly their own department");
        }
        assertTrue(containsId(listContent(a, "/departments?size=100"), f.deptA()));
        assertFalse(containsId(listContent(a, "/departments?size=100"), f.deptB()));

        assertTrue(containsId(listContent(a, "/faculty?size=100"), f.facA()));
        assertFalse(containsId(listContent(a, "/faculty?size=100"), f.facB()));

        assertTrue(containsId(listContent(a, "/classrooms?size=100"), f.roomA()));
        assertFalse(containsId(listContent(a, "/classrooms?size=100"), f.roomB()));

        assertTrue(containsId(listContent(a, "/subjects?size=100"), f.subA()));
        assertEquals(0, sizeOf(listContent(b, "/subjects?size=100")));

        // Mirrored for the sibling HOD.
        assertTrue(containsId(listContent(b, "/faculty?size=100"), f.facB()));
        assertFalse(containsId(listContent(b, "/faculty?size=100"), f.facA()));
    }

    @Test
    void hodRequestingAnotherDepartmentFilterIsRejected() throws Exception {
        Fixture f = fixture();
        String a = token(f.hodA(), HOD_PW);

        for (String path : new String[] {
            "/faculty?size=100&departmentId=" + f.deptB(),
            "/subjects?size=100&departmentId=" + f.deptB(),
        }) {
            MvcResult result = mockMvc.perform(get(path).header("Authorization", "Bearer " + a))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
            assertTrue(message(result).contains("your own department"),
                "expected an own-department rejection for " + path + " but got: " + message(result));
        }
    }

    @Test
    void hodCannotReadAnotherDepartmentsRecordsById() throws Exception {
        Fixture f = fixture();
        String a = token(f.hodA(), HOD_PW);

        mockMvc.perform(get("/faculty/" + f.facB()).header("Authorization", "Bearer " + a))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/classrooms/" + f.roomB()).header("Authorization", "Bearer " + a))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/departments/" + f.deptB()).header("Authorization", "Bearer " + a))
            .andExpect(status().isForbidden());
    }

    // ── Dashboard ───────────────────────────────────────────────────────

    @Test
    void dashboardCountsAreScopedToOwnDepartment() throws Exception {
        Fixture f = fixture();
        String a = token(f.hodA(), HOD_PW);
        String b = token(f.hodB(), HOD_PW);

        JsonNode statsA = data(mockMvc.perform(get("/dashboard/stats").header("Authorization", "Bearer " + a))
            .andExpect(status().isOk()).andReturn());
        JsonNode statsB = data(mockMvc.perform(get("/dashboard/stats").header("Authorization", "Bearer " + b))
            .andExpect(status().isOk()).andReturn());

        assertEquals(1, statsA.get("totalDepartments").asLong(), "HOD sees only their own department");
        assertEquals(1, statsA.get("totalFaculty").asLong(), "one faculty created in dept A");
        assertEquals(1, statsA.get("totalClassrooms").asLong(), "one classroom created in dept A");
        assertEquals(1, statsA.get("totalSubjects").asLong(), "one subject created in dept A");

        assertEquals(1, statsB.get("totalFaculty").asLong());
        assertEquals(0, statsB.get("totalSubjects").asLong(), "dept B has no subjects");

        // The whole point: these are department figures, not college-wide.
        JsonNode statsAdmin = data(mockMvc.perform(get("/dashboard/stats").header("Authorization", "Bearer " + f.admin()))
            .andExpect(status().isOk()).andReturn());
        assertTrue(statsAdmin.get("totalFaculty").asLong() > statsA.get("totalFaculty").asLong(),
            "SUPER_ADMIN dashboard must remain global and therefore larger");
    }

    // ── Read-only department master data ────────────────────────────────

    @Test
    void hodHasReadOnlyDepartmentAccess() throws Exception {
        Fixture f = fixture();
        String a = token(f.hodA(), HOD_PW);

        mockMvc.perform(get("/departments/" + f.deptA()).header("Authorization", "Bearer " + a))
            .andExpect(status().isOk());

        mockMvc.perform(put("/departments/" + f.deptA())
                .header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed-" + unique("n") + "\",\"building\":\"B\",\"contactPhone\":\"9876543210\"}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(patch("/departments/" + f.deptA() + "/archive")
                .header("Authorization", "Bearer " + a))
            .andExpect(status().isForbidden());

        mockMvc.perform(patch("/departments/" + f.deptA() + "/restore")
                .header("Authorization", "Bearer " + a))
            .andExpect(status().isForbidden());

        // A COLLEGE_ADMIN-equivalent (SUPER_ADMIN) keeps full management.
        mockMvc.perform(patch("/departments/" + f.deptA() + "/archive")
                .header("Authorization", "Bearer " + f.admin()))
            .andExpect(status().isOk());
    }

    // ── Write bodies may not escape the HOD's department ────────────────

    @Test
    void hodCannotReassignOwnFacultyIntoAnotherDepartment() throws Exception {
        Fixture f = fixture();
        String a = token(f.hodA(), HOD_PW);

        MvcResult result = mockMvc.perform(put("/faculty/" + f.facA())
                .header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeId\":\"EMP-REASSIGN\",\"firstName\":\"Dept\",\"lastName\":\"Member\","
                    + "\"email\":\"emp-reassign@college.edu\",\"departmentId\":" + f.deptB()
                    + ",\"designation\":\"Professor\",\"maxDailyHours\":6,\"maxWeeklyHours\":24}"))
            .andExpect(status().isUnprocessableEntity())
            .andReturn();
        assertTrue(message(result).contains("your own department"), message(result));

        // The faculty is still in the original department.
        JsonNode faculty = data(mockMvc.perform(get("/faculty/" + f.facA())
                .header("Authorization", "Bearer " + f.admin()))
            .andExpect(status().isOk()).andReturn());
        assertEquals(f.deptA(), faculty.get("departmentId").asLong());
    }

    @Test
    void hodCannotCreateRecordsInAnotherDepartment() throws Exception {
        Fixture f = fixture();
        String a = token(f.hodA(), HOD_PW);
        String room = "X-" + seq.incrementAndGet();

        // Blocked before it can be written — the method-security guard rejects
        // the foreign departmentId (403), and the service-level scope check
        // (422) is the second line of defence if the guard is ever bypassed.
        MvcResult result = mockMvc.perform(post("/classrooms")
                .header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomNumber\":\"" + room + "\",\"roomName\":\"" + room + "\",\"building\":\"B1\","
                    + "\"departmentId\":" + f.deptB() + ",\"roomType\":\"LECTURE_HALL\",\"capacity\":40,\"floor\":1}"))
            .andReturn();
        int status = result.getResponse().getStatus();
        assertTrue(status == 403 || status == 422,
            "expected the cross-department classroom create to be rejected, got " + status);

        // And nothing was persisted in either department.
        JsonNode all = listContent(f.admin(), "/classrooms?size=200&search=" + room);
        assertEquals(0, sizeOf(all), "no classroom may be created for another department");
    }

    @Test
    void hodCanAssignSubjectToFacultyOfAnotherDepartmentWithinSameCollege() throws Exception {
        Fixture f = fixture();
        String a = token(f.hodA(), HOD_PW);

        // Own department, own year/section — faculty from the SIBLING department of
        // the SAME college. Cross-department assignment inside one college is
        // allowed; only cross-COLLEGE assignment is refused (covered separately in
        // MultiCollegeE2ETest).
        MvcResult result = mockMvc.perform(post("/subjects")
                .header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectCode\":\"" + unique("SUB") + "\",\"subjectName\":\"CrossDeptFaculty\","
                    + "\"departmentId\":" + f.deptA()
                    + ",\"academicYearId\":" + f.yearA()
                    + ",\"sectionId\":" + f.sectionA()
                    + ",\"facultyId\":" + f.facB()
                    + ",\"semester\":1,\"credits\":3,\"theoryHours\":3,\"practicalHours\":0}"))
            .andExpect(status().isCreated())
            .andReturn();
        assertEquals(f.facB(), data(result).get("facultyId").asLong(),
            "a sibling-department faculty member of the same college must be assignable");
    }

    @Test
    void hodCannotUseAnotherDepartmentsAcademicYearOrSection() throws Exception {
        Fixture f = fixture();
        String a = token(f.hodA(), HOD_PW);

        MvcResult year = mockMvc.perform(post("/subjects")
                .header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectCode\":\"" + unique("SUB") + "\",\"subjectName\":\"CrossYear\","
                    + "\"departmentId\":" + f.deptA()
                    + ",\"academicYearId\":" + f.yearB()
                    + ",\"sectionId\":" + f.sectionA()
                    + ",\"semester\":1,\"credits\":3,\"theoryHours\":3,\"practicalHours\":0}"))
            .andExpect(status().isUnprocessableEntity())
            .andReturn();
        assertTrue(message(year).contains("academic year"), message(year));

        MvcResult section = mockMvc.perform(post("/classrooms")
                .header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomNumber\":\"Y-" + unique("") + "\",\"roomName\":\"Y\",\"building\":\"B1\","
                    + "\"departmentId\":" + f.deptA()
                    + ",\"academicYearId\":" + f.yearA()
                    + ",\"sectionId\":" + f.sectionB()
                    + ",\"roomType\":\"LECTURE_HALL\",\"capacity\":40,\"floor\":1}"))
            .andExpect(status().isUnprocessableEntity())
            .andReturn();
        assertTrue(message(section).contains("section"), message(section));
    }

    // ── Focused regression tests for the remaining isolation gaps ─────────

    @Test
    void hodRoomUtilizationReportCountsOnlyOwnDepartmentRooms() throws Exception {
        Fixture f = fixture();
        String a = token(f.hodA(), HOD_PW);
        String b = token(f.hodB(), HOD_PW);

        // Each HOD's own single room is counted, and the sibling department's
        // room is not. Before the fix the report fell back to every classroom in
        // the college, so both HODs saw the same total.
        JsonNode reportA = data(mockMvc.perform(get("/reports/rooms/utilization")
                .header("Authorization", "Bearer " + a))
            .andExpect(status().isOk()).andReturn());
        JsonNode reportB = data(mockMvc.perform(get("/reports/rooms/utilization")
                .header("Authorization", "Bearer " + b))
            .andExpect(status().isOk()).andReturn());

        assertEquals(1, reportA.get("totalRooms").asLong(),
            "HOD must only see their own department's rooms");
        assertEquals(1, reportB.get("totalRooms").asLong(),
            "sibling HOD must only see their own department's rooms");

        // A platform administrator keeps the college/global view.
        JsonNode adminReport = data(mockMvc.perform(get("/reports/rooms/utilization")
                .header("Authorization", "Bearer " + f.admin()))
            .andExpect(status().isOk()).andReturn());
        assertTrue(adminReport.get("totalRooms").asLong() > 1,
            "SUPER_ADMIN must retain the unrestricted room view");
    }

    @Test
    void hodCannotCreateDepartments() throws Exception {
        Fixture f = fixture();
        String a = token(f.hodA(), HOD_PW);

        // A structurally valid body, so @Valid passes and the authorization
        // guard is what denies the request.
        MvcResult result = mockMvc.perform(post("/departments")
                .header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"HODISO-NEW-" + unique("") + "\",\"building\":\"" + unique("Blk") + "\","
                    + "\"contactEmail\":\"" + unique("d") + "@college.edu\",\"contactPhone\":\"9876543210\"}"))
            .andExpect(status().isForbidden())
            .andReturn();

        // The read-only view is still intact for the HOD afterwards.
        mockMvc.perform(get("/departments/" + f.deptA()).header("Authorization", "Bearer " + a))
            .andExpect(status().isOk());
    }

    @Test
    void unassignedHodIsDeniedOnOwnTimetablesWithActionableMessage() throws Exception {
        String unassigned = createUnassignedHod();
        String token = token(unassigned, HOD_PW);

        // /timetable/my used to fall through to the college-wide branch for any
        // HOD without a department, exposing every department's timetables.
        MvcResult result = mockMvc.perform(get("/timetable/my")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnprocessableEntity())
            .andReturn();
        assertTrue(message(result).contains("not linked to a department"), message(result));

        // The per-id guards now report the identical cause and message instead
        // of a bare 403, so every denial is actionable in the same way.
        MvcResult forbidden = mockMvc.perform(get("/departments/1")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnprocessableEntity())
            .andReturn();
        assertTrue(message(forbidden).contains("not linked to a department"), message(forbidden));
    }

    @Test
    void superAdminCanEditArchiveAndRestoreDepartments() throws Exception {
        Fixture f = fixture();
        String admin = f.admin();
        String renamed = "HODISO-RENAMED-" + unique("");

        mockMvc.perform(put("/departments/" + f.deptA())
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + renamed + "\",\"building\":\"B9\",\"contactPhone\":\"9876543210\","
                    + "\"years\":[{\"yearLabel\":\"Year-"
                    + unique("") + "\",\"sections\":[\"A\"]}]}"))
            .andExpect(status().isOk());

        JsonNode afterEdit = data(mockMvc.perform(get("/departments/" + f.deptA())
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk()).andReturn());
        assertEquals(renamed, afterEdit.get("name").asText());

        mockMvc.perform(patch("/departments/" + f.deptA() + "/archive")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk());
        JsonNode archived = data(mockMvc.perform(get("/departments/" + f.deptA())
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk()).andReturn());
        assertTrue(archived.get("isArchived").asBoolean());

        mockMvc.perform(patch("/departments/" + f.deptA() + "/restore")
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk());
        JsonNode restored = data(mockMvc.perform(get("/departments/" + f.deptA())
                .header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk()).andReturn());
        assertFalse(restored.get("isArchived").asBoolean());
    }

    @Test
    void hodCrossDepartmentAccessIsDeniedOnEverySurface() throws Exception {
        Fixture f = fixture();
        long subB = createSubject(f.admin(), f.deptB(), f.yearB(), f.sectionB(), f.facB());
        String a = token(f.hodA(), HOD_PW);

        // Reads of a sibling department's records.
        mockMvc.perform(get("/departments/" + f.deptB()).header("Authorization", "Bearer " + a))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/faculty/" + f.facB()).header("Authorization", "Bearer " + a))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/subjects/" + subB).header("Authorization", "Bearer " + a))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/classrooms/" + f.roomB()).header("Authorization", "Bearer " + a))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/faculty/" + f.facB()).header("Authorization", "Bearer " + a))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/availability/faculty/" + f.facB()).header("Authorization", "Bearer " + a))
            .andExpect(status().isForbidden());

        // Writes and department-management actions on a sibling department.
        mockMvc.perform(put("/departments/" + f.deptB())
                .header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"HODISO-HIJACK-" + unique("") + "\",\"building\":\"X\","
                    + "\"contactPhone\":\"9876543210\"}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(patch("/departments/" + f.deptB() + "/archive")
                .header("Authorization", "Bearer " + a))
            .andExpect(status().isForbidden());
        mockMvc.perform(patch("/departments/" + f.deptB() + "/restore")
                .header("Authorization", "Bearer " + a))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/departments/" + f.deptB())
                .header("Authorization", "Bearer " + a))
            .andExpect(status().isForbidden());

        // None of it changed the sibling department.
        JsonNode deptB = data(mockMvc.perform(get("/departments/" + f.deptB())
                .header("Authorization", "Bearer " + f.admin()))
            .andExpect(status().isOk()).andReturn());
        assertFalse(deptB.get("isArchived").asBoolean());
        assertEquals(1, sizeOf(listContent(a, "/departments?size=100")));
    }

    @Test
    void hodCannotDetachOwnRecordsBySubmittingNullDepartment() throws Exception {
        Fixture f = fixture();
        String a = token(f.hodA(), HOD_PW);

        // Faculty
        MvcResult faculty = mockMvc.perform(put("/faculty/" + f.facA())
                .header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeId\":\"EMP-DETACH\",\"firstName\":\"No\",\"lastName\":\"Department\","
                    + "\"email\":\"emp-detach@college.edu\",\"designation\":\"Professor\","
                    + "\"maxDailyHours\":6,\"maxWeeklyHours\":24}"))
            .andExpect(status().isUnprocessableEntity())
            .andReturn();
        assertTrue(message(faculty).contains("your department"), message(faculty));

        // Classroom
        MvcResult room = mockMvc.perform(put("/classrooms/" + f.roomA())
                .header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roomNumber\":\"RD-" + unique("") + "\",\"roomName\":\"D\",\"building\":\"B1\","
                    + "\"roomType\":\"LECTURE_HALL\",\"capacity\":40,\"floor\":1}"))
            .andExpect(status().isUnprocessableEntity())
            .andReturn();
        assertTrue(message(room).contains("your department"), message(room));

        // Subject
        MvcResult subject = mockMvc.perform(put("/subjects/" + f.subA())
                .header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectCode\":\"DET" + unique("") + "\",\"subjectName\":\"Detached\","
                    + "\"subjectType\":\"THEORY\",\"theoryHours\":3,\"practicalHours\":0,"
                    + "\"semester\":1}"))
            .andExpect(status().isUnprocessableEntity())
            .andReturn();
        assertTrue(message(subject).contains("your department"), message(subject));

        // All three are still in their original department.
        assertEquals(f.deptA(), data(mockMvc.perform(get("/faculty/" + f.facA())
                .header("Authorization", "Bearer " + f.admin()))
            .andExpect(status().isOk()).andReturn()).get("departmentId").asLong());
        assertEquals(f.deptA(), data(mockMvc.perform(get("/classrooms/" + f.roomA())
                .header("Authorization", "Bearer " + f.admin()))
            .andExpect(status().isOk()).andReturn()).get("departmentId").asLong());
        assertEquals(f.deptA(), data(mockMvc.perform(get("/subjects/" + f.subA())
                .header("Authorization", "Bearer " + f.admin()))
            .andExpect(status().isOk()).andReturn()).get("departmentId").asLong());
    }
}
