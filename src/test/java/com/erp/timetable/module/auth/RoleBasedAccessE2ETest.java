package com.erp.timetable.module.auth;

import com.erp.timetable.module.auth.entity.Role;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.RoleRepository;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role-based access control tests (real logins → JWT → method security).
 *
 * <p>Verifies the 4-role mapping end-to-end:
 * SUPER_ADMIN — global write access; HOD — department-scoped writes (own
 * department only); FACULTY — self-scoped availability/reports; STUDENT —
 * blocked from every management endpoint and served only by the self-scoped
 * {@code /timetable/my} endpoint.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:role_access_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class RoleBasedAccessE2ETest {

    private static final String PASSWORD = "Pass@1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private FacultyRepository facultyRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final AtomicLong seq = new AtomicLong();

    // ── Helpers ────────────────────────────────────────────────────────

    private Department newDepartment(String name) {
        Department dept = Department.builder()
            .name(name)
            .hodName("HOD " + name)
            .contactEmail(name.replaceAll("\\s+", "").toLowerCase() + "@college.edu")
            .contactPhone("9876543210")
            .building("Block-X")
            .isArchived(false)
            .build();
        AcademicYear year = AcademicYear.builder().yearLabel("1st Year").isEnabled(true).build();
        year.addSection(Section.builder().name("A").studentStrength(60).status("ACTIVE").build());
        dept.addAcademicYear(year);
        return departmentRepository.save(dept);
    }

    private User saveUser(String username, RoleName role, Department dept) {
        Role roleEntity = roleRepository.findByName(role)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name(role)
                .description(role.name())
                .build()));
        User user = User.builder()
            .username(username)
            .email(username + "@college.edu")
            .password(passwordEncoder.encode(PASSWORD))
            .fullName(username)
            .isActive(true)
            .department(dept)
            .build();
        user.addRole(roleEntity);
        return userRepository.save(user);
    }

    private Faculty saveFaculty(String employeeId, User owner, Department dept, Long userId) {
        return facultyRepository.save(Faculty.builder()
            .employeeId(employeeId)
            .firstName("RBAC")
            .lastName("Faculty")
            .email(employeeId + "@college.edu")
            .phone("9876543210")
            .department(dept)
            .designation("Professor")
            .maxDailyHours(6)
            .maxWeeklyHours(24)
            .status("AVAILABLE")
            .userId(userId)
            .build());
    }

    private String unique() {
        return "U" + seq.incrementAndGet();
    }

    private String loginAccessToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("data").get("accessToken").asText();
    }

    private JsonNode authedGet(String token, String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                .header("Authorization", "Bearer " + token))
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ── FACULTY ────────────────────────────────────────────────────────

    @Test
    void student_login_isRejectedWith422() throws Exception {
        Department dept = newDepartment("R Bac CSE " + unique());
        saveUser("rbac_student", RoleName.ROLE_STUDENT, dept);

        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"rbac_student\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(body.get("message").asText().toLowerCase().contains("student login"),
            "rejection message should explain student login is unavailable");
        assertEquals(0, body.at("/data/accessToken").size(),
            "no access token may be issued to a student account");
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void student_cannotListDepartmentsOrFaculty() throws Exception {
        mockMvc.perform(get("/departments"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/faculty"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/subjects"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/dashboard/stats"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/timetable/1"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void student_cannotCreateFacultyOrSubject() throws Exception {
        Department dept = newDepartment("R Bac BlockD " + unique());

        // Valid bodies: @Valid runs during argument resolution (BEFORE the
        // method-security interceptor), so a malformed body would surface as
        // 400 from validation instead of exercising the RBAC guard.
        String empId = "FAC-" + unique();
        String facBody = "{\"employeeId\":\"" + empId + "\",\"firstName\":\"X\",\"lastName\":\"Y\","
            + "\"email\":\"" + empId + "@college.edu\",\"departmentId\":" + dept.getId() + "}";
        mockMvc.perform(post("/faculty")
                .contentType(MediaType.APPLICATION_JSON)
                .content(facBody))
            .andExpect(status().isForbidden());

        String code = "RBS" + unique().replace("U", "");
        String subjBody = "{\"subjectCode\":\"" + code + "\",\"subjectName\":\"Student Subj\","
            + "\"departmentId\":" + dept.getId() + ",\"semester\":1}";
        mockMvc.perform(post("/subjects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(subjBody))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void student_cannotReadAnyTimetable() throws Exception {
        Department dept = newDepartment("R Bac BlockE " + unique());
        AcademicYear year = dept.getAcademicYears().get(0);
        Section section = year.getSections().get(0);

        mockMvc.perform(get("/timetable/department/" + dept.getId()))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/timetable/section/" + section.getId() + "/semester/1"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/availability/faculty/1"))
            .andExpect(status().isForbidden());
    }

    // ── FACULTY ────────────────────────────────────────────────────────

    @Test
    void faculty_cannotReadOrSaveAvailability_evenOwn() throws Exception {
        Department deptA = newDepartment("R Bac FacA " + unique());
        Department deptB = newDepartment("R Bac FacB " + unique());
        User userA = saveUser("rbac_fac_a_" + unique(), RoleName.ROLE_FACULTY, deptA);
        User userB = saveUser("rbac_fac_b_" + unique(), RoleName.ROLE_FACULTY, deptB);
        Faculty facA = saveFaculty("FAC-" + unique(), userA, deptA, userA.getId());
        Faculty facB = saveFaculty("FAC-" + unique(), userB, deptB, userB.getId());

        String tokenA = loginAccessToken(userA.getUsername());

        // Availability is management data: the Faculty role may not save OR read
        // any availability matrix — not even their own.
        mockMvc.perform(post("/availability/faculty/" + facA.getId())
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/availability/faculty/" + facB.getId())
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/availability/faculty/" + facA.getId())
                .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isForbidden());
    }

    @Test
    void faculty_report_denied_evenOwn() throws Exception {
        Department deptA = newDepartment("R Bac RepA " + unique());
        Department deptB = newDepartment("R Bac RepB " + unique());
        User userA = saveUser("rbac_rep_a_" + unique(), RoleName.ROLE_FACULTY, deptA);
        User userB = saveUser("rbac_rep_b_" + unique(), RoleName.ROLE_FACULTY, deptB);
        Faculty facA = saveFaculty("FAC-" + unique(), userA, deptA, userA.getId());
        Faculty facB = saveFaculty("FAC-" + unique(), userB, deptB, userB.getId());

        String tokenA = loginAccessToken(userA.getUsername());

        // Reports are management data: the Faculty role sees their schedule via
        // /timetable/my, never through the report centre — even for themselves.
        mockMvc.perform(get("/reports/faculty/" + facA.getId())
                .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/faculty/" + facB.getId())
                .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/rooms/utilization")
                .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isForbidden());
    }

    // ── HOD ────────────────────────────────────────────────────────────

    @Test
    void hod_subjectCreate_ownDepartmentOk_foreignDenied() throws Exception {
        Department own = newDepartment("R Bac HodOwn " + unique());
        Department foreign = newDepartment("R Bac HodForeign " + unique());
        saveUser("rbac_hod_subj", RoleName.ROLE_HOD, own);
        String token = loginAccessToken("rbac_hod_subj");

        AcademicYear ownYear = own.getAcademicYears().get(0);
        Section ownSection = ownYear.getSections().get(0);
        String code = "RBT" + unique().replace("U", "");
        String ownBody = "{\"subjectCode\":\"" + code + "\",\"subjectName\":\"Own Subject\","
            + "\"departmentId\":" + own.getId() + ",\"academicYearId\":" + ownYear.getId()
            + ",\"sectionId\":" + ownSection.getId() + ",\"semester\":1,\"credits\":3,\"theoryHours\":3,\"practicalHours\":0,\"subjectType\":\"THEORY\"}";
        mockMvc.perform(post("/subjects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ownBody))
            .andExpect(status().isCreated());

        String foreignCode = "RBT" + unique().replace("U", "") + "F";
        String foreignBody = "{\"subjectCode\":\"" + foreignCode + "\",\"subjectName\":\"Foreign Subject\","
            + "\"departmentId\":" + foreign.getId() + ",\"semester\":2}";
        mockMvc.perform(post("/subjects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(foreignBody))
            .andExpect(status().isForbidden());
    }

    @Test
    void hod_facultyCreate_denied_forOwnAndForeignDepartment() throws Exception {
        Department own = newDepartment("R Bac HodFac " + unique());
        Department foreign = newDepartment("R Bac HodFacFor " + unique());
        saveUser("rbac_hod_fac", RoleName.ROLE_HOD, own);
        String token = loginAccessToken("rbac_hod_fac");

        // Creating faculty is a college-administration action: an HOD is
        // read-only on their own department, so even the OWN department is
        // refused. (Viewing/editing existing own-department faculty is still
        // allowed - see the faculty lifecycle E2E suite.)
        String ownEmpId = "FAC-" + unique();
        String ownBody = "{\"employeeId\":\"" + ownEmpId + "\",\"firstName\":\"X\",\"lastName\":\"Y\","
            + "\"email\":\"" + ownEmpId + "@college.edu\",\"departmentId\":" + own.getId() + ","
            + "\"maxDailyHours\":6,\"maxWeeklyHours\":24}";
        mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ownBody))
            .andExpect(status().isForbidden());

        String foreignEmpId = "FAC-" + unique() + "F";
        String foreignBody = "{\"employeeId\":\"" + foreignEmpId + "\",\"firstName\":\"X\",\"lastName\":\"Y\","
            + "\"email\":\"" + foreignEmpId + "@college.edu\",\"departmentId\":" + foreign.getId() + ","
            + "\"maxDailyHours\":6,\"maxWeeklyHours\":24}";
        mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(foreignBody))
            .andExpect(status().isForbidden());
    }

    @Test
    void hod_departmentUpdate_onlyOwn_foreignDenied() throws Exception {
        Department own = newDepartment("R Bac HodUpdOwn " + unique());
        Department foreign = newDepartment("R Bac HodUpdFor " + unique());
        saveUser("rbac_hod_upd", RoleName.ROLE_HOD, own);
        String token = loginAccessToken("rbac_hod_upd");

        // Valid body (name + existing year/section) so bean validation passes
        // and the guard is what rejects the foreign department.
        String yearsJson = "\"years\":[{\"yearLabel\":\"1st Year\",\"sections\":[\"A\"]}]";
        mockMvc.perform(put("/departments/" + foreign.getId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"RenamedForeign\",\"building\":\"Block-Y\"," + yearsJson + "}"))
            .andExpect(status().isForbidden());

        // Own department is READ-ONLY for an HOD. Department master data is a
        // college-administration action, so canEditDepartment rejects the HOD
        // for their OWN department too — both cases are 403.
        mockMvc.perform(put("/departments/" + own.getId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + own.getName() + "\",\"building\":\"Block-X\"," + yearsJson + "}"))
            .andExpect(status().isForbidden());

        // Positive control: the identical update by a COLLEGE_ADMIN of the same
        // college still succeeds, proving the 403s above are the HOD read-only
        // policy and not a malformed body.
        User collegeAdmin = saveUser("rbac_coladmin_upd", RoleName.ROLE_COLLEGE_ADMIN, null);
        collegeAdmin.setCollege(own.getCollege());
        userRepository.save(collegeAdmin);
        mockMvc.perform(put("/departments/" + own.getId())
                .header("Authorization", "Bearer " + loginAccessToken("rbac_coladmin_upd"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + own.getName() + "\",\"building\":\"Block-X\"," + yearsJson + "}"))
            .andExpect(status().isOk());
    }

    @Test
    void hod_cannotSaveAvailabilityOfForeignFaculty() throws Exception {
        Department own = newDepartment("R Bac HodAvOwn " + unique());
        Department foreign = newDepartment("R Bac HodAvFor " + unique());
        saveUser("rbac_hod_av", RoleName.ROLE_HOD, own);
        User forUser = saveUser("rbac_fac_av_" + unique(), RoleName.ROLE_FACULTY, foreign);
        Faculty facForeign = saveFaculty("FAC-" + unique(), forUser, foreign, forUser.getId());
        String token = loginAccessToken("rbac_hod_av");

        mockMvc.perform(post("/availability/faculty/" + facForeign.getId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isForbidden());
    }

    // ── SUPER_ADMIN ────────────────────────────────────────────────────

    @Test
    void admin_facultyCreate_inAnyDepartment_allowed() throws Exception {
        Department deptA = newDepartment("R Bac AdmA " + unique());
        Department deptB = newDepartment("R Bac AdmB " + unique());
        saveUser("rbac_admin", RoleName.ROLE_SUPER_ADMIN, null);
        String token = loginAccessToken("rbac_admin");

        for (Department dept : List.of(deptA, deptB)) {
            String body = "{\"employeeId\":\"FAC-" + unique().replace("U", "") + "\",\"firstName\":\"A\",\"lastName\":\"B\","
                + "\"email\":\"" + unique() + "@college.edu\",\"departmentId\":" + dept.getId() + ","
                + "\"maxDailyHours\":6,\"maxWeeklyHours\":24}";
            mockMvc.perform(post("/faculty")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isCreated());
        }
    }
}