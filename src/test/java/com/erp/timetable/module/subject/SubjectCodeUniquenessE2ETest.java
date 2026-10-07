package com.erp.timetable.module.subject;

import com.erp.timetable.module.auth.entity.Role;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.RoleRepository;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.DepartmentRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Subject-code reuse rule end-to-end.
 *
 * <p>Within a department a subject code maps to exactly ONE academic year, but it
 * MAY be repeated across the different SECTIONS of that year. In other words:
 * <ul>
 *   <li>CS266 on CSE / 1st Year / sections A, B and C  → all allowed;</li>
 *   <li>CS266 on CSE / 2nd, 3rd or 4th Year            → rejected;</li>
 *   <li>CS266 on ECE / 1st Year                        → allowed (new department);</li>
 *   <li>same NAME with a different code                → allowed;</li>
 *   <li>the exact same code twice for one section+year → rejected.</li>
 * </ul>
 * Drives the real HTTP API with a real SUPER_ADMIN JWT against a fresh in-memory
 * H2, which builds its schema from the entity (Flyway is disabled on the h2
 * profile), so this also proves no stale global uniqueness remains in the model.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:subject_code_uniqueness_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class SubjectCodeUniquenessE2ETest {

    private static final String PASSWORD = "Pass@1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final AtomicLong seq = new AtomicLong();

    // ── Helpers ────────────────────────────────────────────────────────

    /** Department with the given year labels, each carrying sections A, B and C. */
    private Department newDepartment(String name, String... yearLabels) {
        Department dept = Department.builder()
            .name(name + " " + unique())
            .hodName("HOD " + name)
            .contactEmail("dept" + unique() + "@college.edu")
            .contactPhone("9876543210")
            .building("Block-X")
            .isArchived(false)
            .build();
        for (String label : yearLabels) {
            AcademicYear year = AcademicYear.builder().yearLabel(label).isEnabled(true).build();
            for (String sec : new String[]{"A", "B", "C"}) {
                year.addSection(Section.builder().name(sec).studentStrength(60).status("ACTIVE").build());
            }
            dept.addAcademicYear(year);
        }
        return departmentRepository.save(dept);
    }

    private AcademicYear year(Department dept, String label) {
        return dept.getAcademicYears().stream()
            .filter(y -> label.equals(y.getYearLabel()))
            .findFirst().orElseThrow();
    }

    private Section section(AcademicYear year, String name) {
        return year.getSections().stream()
            .filter(s -> name.equals(s.getName()))
            .findFirst().orElseThrow();
    }

    private String subjectBody(String code, String name, Department dept, AcademicYear year, Section section) {
        return "{\"subjectCode\":\"" + code + "\",\"subjectName\":\"" + name + "\","
            + "\"departmentId\":" + dept.getId() + ",\"academicYearId\":" + year.getId()
            + ",\"sectionId\":" + section.getId()
            + ",\"semester\":1,\"credits\":3,\"theoryHours\":3,\"practicalHours\":0,"
            + "\"subjectType\":\"THEORY\",\"sessionBlockSize\":1,\"isActive\":true}";
    }

    private String unique() {
        return "S" + seq.incrementAndGet();
    }

    private User newSuperAdmin() {
        Role role = roleRepository.findByName(RoleName.ROLE_SUPER_ADMIN)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name(RoleName.ROLE_SUPER_ADMIN)
                .description(RoleName.ROLE_SUPER_ADMIN.name())
                .build()));
        String username = "subj_admin_" + unique();
        User user = User.builder()
            .username(username)
            .email(username + "@college.edu")
            .password(passwordEncoder.encode(PASSWORD))
            .fullName("Subject Super Admin")
            .isActive(true)
            .build();
        user.addRole(role);
        return userRepository.save(user);
    }

    private String loginAccessToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andReturn();
        assertEquals(200, result.getResponse().getStatus(), "login must succeed for " + username);
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("data").get("accessToken").asText();
    }

    private int create(String token, String body) throws Exception {
        return mockMvc.perform(post("/subjects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn().getResponse().getStatus();
    }

    private long createId(String token, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/subjects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
        assertEquals(201, result.getResponse().getStatus(), "setup create must succeed");
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("data").get("id").asLong();
    }

    private int update(String token, long id, String body) throws Exception {
        return mockMvc.perform(put("/subjects/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn().getResponse().getStatus();
    }

    // ── Core cases ─────────────────────────────────────────────────────

    @Test
    void subjectCode_mapsToOneYear_perDepartment_butRepeatsAcrossSections() throws Exception {
        String token = loginAccessToken(newSuperAdmin().getUsername());
        Department cse = newDepartment("SubjRule CSE", "1st Year", "2nd Year", "3rd Year", "4th Year");
        Department ece = newDepartment("SubjRule ECE", "1st Year");

        AcademicYear cse1 = year(cse, "1st Year");
        AcademicYear cse2 = year(cse, "2nd Year");
        AcademicYear cse3 = year(cse, "3rd Year");
        AcademicYear cse4 = year(cse, "4th Year");
        AcademicYear ece1 = year(ece, "1st Year");

        // A, B, C: same code across DIFFERENT sections of the same 1st year → allowed.
        assertEquals(201, create(token, subjectBody("CS266", "Tamil", cse, cse1, section(cse1, "A"))));
        assertEquals(201, create(token, subjectBody("CS266", "Tamil", cse, cse1, section(cse1, "B"))));
        assertEquals(201, create(token, subjectBody("CS266", "Tamil", cse, cse1, section(cse1, "C"))));

        // Same code for the SAME section+year again → rejected.
        assertEquals(422, create(token, subjectBody("CS266", "Tamil", cse, cse1, section(cse1, "A"))));

        // D, E, F: same code in another year of the SAME department → rejected.
        assertEquals(422, create(token, subjectBody("CS266", "Tamil", cse, cse2, section(cse2, "A"))));
        assertEquals(422, create(token, subjectBody("CS266", "Tamil", cse, cse3, section(cse3, "A"))));
        assertEquals(422, create(token, subjectBody("CS266", "Tamil", cse, cse4, section(cse4, "A"))));

        // G: a different department is a separate code space → allowed.
        assertEquals(201, create(token, subjectBody("CS266", "Tamil", ece, ece1, section(ece1, "A"))));

        // H: same NAME with a different code in the same dept+year → allowed.
        assertEquals(201, create(token, subjectBody("CS267", "Tamil", cse, cse1, section(cse1, "A"))));
    }

    // ── Update semantics ───────────────────────────────────────────────

    @Test
    void updateSubject_preservesCodeYearMapping_andAllowsSectionMoves() throws Exception {
        String token = loginAccessToken(newSuperAdmin().getUsername());
        Department cse = newDepartment("SubjUpdate CSE", "1st Year", "2nd Year");
        Department ece = newDepartment("SubjUpdate ECE", "1st Year");

        AcademicYear cse1 = year(cse, "1st Year");
        AcademicYear cse2 = year(cse, "2nd Year");
        AcademicYear ece1 = year(ece, "1st Year");
        Section cse1A = section(cse1, "A");
        Section cse1B = section(cse1, "B");
        Section cse1C = section(cse1, "C");
        Section cse2A = section(cse2, "A");
        Section cse2B = section(cse2, "B");
        Section ece1A = section(ece1, "A");

        long idA = createId(token, subjectBody("CS300", "Alpha", cse, cse1, cse1A));
        long idB = createId(token, subjectBody("CS301", "Beta", cse, cse2, cse2A));

        // Unchanged code (same dept+year+section) must not conflict with itself.
        assertEquals(200, update(token, idA, subjectBody("CS300", "Alpha renamed", cse, cse1, cse1A)));

        // Section is not part of the reuse guard → moving within the same year allowed.
        assertEquals(200, update(token, idA, subjectBody("CS300", "Alpha", cse, cse1, cse1B)));

        // Moving B onto A's code while staying in 2nd year → cross-year conflict → rejected.
        assertEquals(422, update(token, idB, subjectBody("CS300", "Beta", cse, cse2, cse2A)));

        // Moving A itself to another year keeps its own code space → allowed.
        assertEquals(200, update(token, idA, subjectBody("CS300", "Alpha", cse, cse2, cse2B)));

        // CS300 now belongs to 2nd year → it can no longer be created in 1st year.
        assertEquals(422, create(token, subjectBody("CS300", "New", cse, cse1, cse1C)));

        // A different department is a separate code space → allowed.
        assertEquals(200, update(token, idB, subjectBody("CS300", "Beta", ece, ece1, ece1A)));
    }
}
