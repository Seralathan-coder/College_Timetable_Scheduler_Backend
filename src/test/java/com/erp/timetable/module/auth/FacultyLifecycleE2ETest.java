package com.erp.timetable.module.auth;

import com.erp.timetable.module.auth.entity.College;
import com.erp.timetable.module.auth.entity.Role;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.CollegeRepository;
import com.erp.timetable.module.auth.repository.RoleRepository;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.department.entity.Department;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused end-to-end tests for the four confirmed Faculty Management fixes:
 *
 * <ol>
 *   <li>deleting a faculty member deactivates (never deletes) its linked login,
 *       so the Login ID can be reused by rebinding the preserved account;</li>
 *   <li>duplicate faculty names are already legal and stay legal;</li>
 *   <li>an edit that omits the create-only password succeeds;</li>
 *   <li>an HOD cannot create faculty (403) but keeps view/edit on own dept.</li>
 * </ol>
 *
 * <p>Runs against a fresh in-memory H2 with the {@code h2} profile, so it never
 * touches the persistent dev database.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:faculty_lifecycle_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class FacultyLifecycleE2ETest {

    private static final String PW = "Pass@1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CollegeRepository collegeRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private FacultyRepository facultyRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final AtomicLong seq = new AtomicLong();

    // ── Helpers ─────────────────────────────────────────────────────────

    private String unique(String prefix) {
        return prefix + seq.incrementAndGet();
    }

    private College newCollege() {
        String code = unique("FC");
        return collegeRepository.save(College.builder()
            .name("Faculty College " + code)
            .code(code)
            .email(code.toLowerCase() + "@college.edu")
            .isActive(true)
            .build());
    }

    private Department newDepartment(String name, College college) {
        return departmentRepository.save(Department.builder()
            .name(name + " " + unique("D"))
            .hodName("HOD")
            .contactEmail(unique("dept") + "@college.edu")
            .contactPhone("9876543210")
            .building("Block-X")
            .college(college)
            .isArchived(false)
            .build());
    }

    private User newUser(String username, RoleName role, Department dept, College college, boolean active) {
        Role roleEntity = roleRepository.findByName(role)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name(role)
                .description(role.name())
                .build()));
        User user = User.builder()
            .username(username)
            .email(username + "@college.edu")
            .password(passwordEncoder.encode(PW))
            .fullName(username)
            .isActive(active)
            .department(dept)
            .college(college)
            .build();
        user.addRole(roleEntity);
        return userRepository.save(user);
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + PW + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return tree(result).get("data").get("accessToken").asText();
    }

    private JsonNode tree(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return tree(result).get("data");
    }

    private String facultyBody(String empId, String first, String last, String email, long deptId,
                               String username, String password) {
        StringBuilder sb = new StringBuilder()
            .append("{\"employeeId\":\"").append(empId)
            .append("\",\"firstName\":\"").append(first)
            .append("\",\"lastName\":\"").append(last)
            .append("\",\"email\":\"").append(email)
            .append("\",\"departmentId\":").append(deptId)
            .append(",\"designation\":\"Professor\",\"maxDailyHours\":6,\"maxWeeklyHours\":24");
        if (username != null) {
            sb.append(",\"username\":\"").append(username).append("\"");
        }
        if (password != null) {
            sb.append(",\"password\":\"").append(password).append("\"");
        }
        return sb.append("}").toString();
    }

    // ── 1 + 2: create, duplicate name, delete, reuse Login ID ────────────

    @Test
    void duplicateFacultyName_withDifferentEmployeeId_isAccepted() throws Exception {
        College college = newCollege();
        User admin = newUser(unique("ca"), RoleName.ROLE_COLLEGE_ADMIN, null, college, true);
        Department dept = newDepartment("Dup Name", college);
        String token = login(admin.getUsername());

        mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(facultyBody("FAC-" + unique("A"), "Amar", "B", unique("a") + "@college.edu", dept.getId(), null, null)))
            .andExpect(status().isCreated());

        // Same human name, different employee ID and email - must be accepted.
        mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(facultyBody("FAC-" + unique("B"), "Amar", "B", unique("b") + "@college.edu", dept.getId(), null, null)))
            .andExpect(status().isCreated());
    }

    @Test
    void deletedFacultyLoginId_isReused_byReactivatingThePreservedAccount() throws Exception {
        College college = newCollege();
        User admin = newUser(unique("ca"), RoleName.ROLE_COLLEGE_ADMIN, null, college, true);
        Department dept = newDepartment("Reuse", college);
        String token = login(admin.getUsername());

        String loginId = unique("faclogin");
        MvcResult created = mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(facultyBody("FAC-" + unique("1"), "Reuse", "One", unique("r1") + "@college.edu",
                    dept.getId(), loginId, PW)))
            .andExpect(status().isCreated())
            .andReturn();
        long facultyId = data(created).get("id").asLong();

        // The login was provisioned and linked.
        User linked = userRepository.findAllByUsername(loginId).get(0);
        assertTrue(Boolean.TRUE.equals(linked.getIsActive()));
        Faculty firstFaculty = facultyRepository.findById(facultyId).orElseThrow();
        assertEquals(linked.getId(), firstFaculty.getUserId());

        // Delete the faculty member.
        mockMvc.perform(delete("/faculty/" + facultyId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());

        assertFalse(facultyRepository.findById(facultyId).isPresent());
        User preserved = userRepository.findById(linked.getId()).orElseThrow();
        assertFalse(Boolean.TRUE.equals(preserved.getIsActive()),
            "the preserved login account must be deactivated");

        // Re-create a DIFFERENT faculty with the SAME Login ID.
        MvcResult recreated = mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(facultyBody("FAC-" + unique("2"), "Reuse", "Two", unique("r2") + "@college.edu",
                    dept.getId(), loginId, PW)))
            .andExpect(status().isCreated())
            .andReturn();

        long newFacultyId = data(recreated).get("id").asLong();
        Faculty secondFaculty = facultyRepository.findById(newFacultyId).orElseThrow();
        assertEquals(linked.getId(), secondFaculty.getUserId(),
            "the preserved account is rebound rather than a duplicate being inserted");

        List<User> sameLogin = userRepository.findAllByUsername(loginId);
        assertEquals(1, sameLogin.size(), "there is still exactly one account for this Login ID");
        assertTrue(Boolean.TRUE.equals(sameLogin.get(0).getIsActive()));

        // The reused Login ID signs in again.
        assertNotNull(login(loginId));
    }

    @Test
    void releasedLoginId_isNotTakenFromAnotherCollege_orFromANonFacultyAccount() throws Exception {
        College collegeA = newCollege();
        User adminA = newUser(unique("caA"), RoleName.ROLE_COLLEGE_ADMIN, null, collegeA, true);
        Department deptA = newDepartment("Isolation A", collegeA);
        String tokenA = login(adminA.getUsername());

        String sharedLogin = unique("shared");
        MvcResult created = mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(facultyBody("FAC-" + unique("3"), "Iso", "A", unique("i1") + "@college.edu",
                    deptA.getId(), sharedLogin, PW)))
            .andExpect(status().isCreated())
            .andReturn();
        mockMvc.perform(delete("/faculty/" + data(created).get("id").asLong())
                .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk());

        // College B may use the SAME Login ID: A's released account must not be
        // stolen (it belongs to another college), so B gets its own account.
        College collegeB = newCollege();
        User adminB = newUser(unique("caB"), RoleName.ROLE_COLLEGE_ADMIN, null, collegeB, true);
        Department deptB = newDepartment("Isolation B", collegeB);
        mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + login(adminB.getUsername()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(facultyBody("FAC-" + unique("4"), "Iso", "B", unique("i2") + "@college.edu",
                    deptB.getId(), sharedLogin, PW)))
            .andExpect(status().isCreated());

        assertEquals(2, userRepository.findAllByUsername(sharedLogin).size(),
            "each college keeps its own account for a shared Login ID");

        // A deactivated NON-faculty account must never be hijacked: create a
        // deactivated HOD with a fresh Login ID and try to reuse it as faculty.
        String hodLogin = unique("hodold");
        User deactivatedHod = newUser(hodLogin, RoleName.ROLE_HOD, deptA, collegeA, false);
        mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(facultyBody("FAC-" + unique("5"), "Iso", "C", unique("i3") + "@college.edu",
                    deptA.getId(), hodLogin, PW)))
            .andExpect(status().isUnprocessableEntity());

        User stillHod = userRepository.findById(deactivatedHod.getId()).orElseThrow();
        assertFalse(Boolean.TRUE.equals(stillHod.getIsActive()));
    }

    // ── 3: update without password ───────────────────────────────────────

    @Test
    void facultyUpdate_withoutPassword_succeeds_andEmptyPassword_isRejected() throws Exception {
        College college = newCollege();
        User admin = newUser(unique("ca"), RoleName.ROLE_COLLEGE_ADMIN, null, college, true);
        Department dept = newDepartment("Edit", college);
        String token = login(admin.getUsername());

        MvcResult created = mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(facultyBody("FAC-" + unique("6"), "Old", "Name", unique("e1") + "@college.edu",
                    dept.getId(), null, null)))
            .andExpect(status().isCreated())
            .andReturn();
        long id = data(created).get("id").asLong();

        // The exact payload the UI now sends on edit: no password / username.
        String updateBody = "{\"employeeId\":\"" + data(created).get("employeeId").asText()
            + "\",\"firstName\":\"New\",\"lastName\":\"Name\",\"email\":\""
            + data(created).get("email").asText() + "\",\"departmentId\":" + dept.getId()
            + ",\"designation\":\"Professor\",\"maxDailyHours\":6,\"maxWeeklyHours\":24,\"status\":\"AVAILABLE\"}";
        mockMvc.perform(put("/faculty/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody))
            .andExpect(status().isOk());

        assertEquals("New", facultyRepository.findById(id).orElseThrow().getFirstName());

        // An empty-string password is still rejected by validation (the create
        // requirement is intentionally unchanged).
        String withEmptyPassword = "{\"employeeId\":\"" + data(created).get("employeeId").asText()
            + "\",\"firstName\":\"New2\",\"lastName\":\"Name\",\"email\":\""
            + data(created).get("email").asText() + "\",\"departmentId\":" + dept.getId()
            + ",\"maxDailyHours\":6,\"maxWeeklyHours\":24,\"username\":\"\",\"password\":\"\"}";
        mockMvc.perform(put("/faculty/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(withEmptyPassword))
            .andExpect(status().isBadRequest());
    }

    // ── 4: HOD create = 403, view/edit preserved ─────────────────────────

    @Test
    void hod_cannotCreateFaculty_butStillViewsAndEditsOwnDepartmentFaculty() throws Exception {
        College college = newCollege();
        User admin = newUser(unique("ca"), RoleName.ROLE_COLLEGE_ADMIN, null, college, true);
        Department dept = newDepartment("HOD Scope", college);
        User hod = newUser(unique("hod"), RoleName.ROLE_HOD, dept, college, true);
        String adminToken = login(admin.getUsername());
        String hodToken = login(hod.getUsername());

        // College Admin still creates faculty.
        MvcResult created = mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(facultyBody("FAC-" + unique("7"), "Hod", "Target", unique("h1") + "@college.edu",
                    dept.getId(), null, null)))
            .andExpect(status().isCreated())
            .andReturn();
        long id = data(created).get("id").asLong();
        String empId = data(created).get("employeeId").asText();
        String email = data(created).get("email").asText();

        // HOD must be refused even when calling the API directly.
        mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + hodToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(facultyBody("FAC-" + unique("8"), "Hod", "Made", unique("h2") + "@college.edu",
                    dept.getId(), null, null)))
            .andExpect(status().isForbidden());

        // Existing HOD read/update on own department's faculty is preserved.
        mockMvc.perform(get("/faculty").header("Authorization", "Bearer " + hodToken))
            .andExpect(status().isOk());
        mockMvc.perform(get("/faculty/" + id).header("Authorization", "Bearer " + hodToken))
            .andExpect(status().isOk());
        mockMvc.perform(put("/faculty/" + id)
                .header("Authorization", "Bearer " + hodToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(facultyBody(empId, "Hod", "Edited", email, dept.getId(), null, null)))
            .andExpect(status().isOk());
    }
}
