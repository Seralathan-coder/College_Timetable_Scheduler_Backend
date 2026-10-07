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

/**
 * Subject Type master values end-to-end.
 *
 * <p>The supported set is THEORY / LAB / GAME / OTHER. The retired ELECTIVE and
 * MANDATORY values - and any unknown value - must be rejected by the request
 * validation with a 400. Runs against a real SUPER_ADMIN JWT on a fresh
 * in-memory H2 (schema built from the entity).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:subject_type_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class SubjectTypeValidationE2ETest {

    private static final String PASSWORD = "Pass@1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final AtomicLong seq = new AtomicLong();

    private String unique() {
        return "T" + seq.incrementAndGet();
    }

    private Department newDepartment(String name) {
        Department dept = Department.builder()
            .name(name + " " + unique())
            .hodName("HOD " + name)
            .contactEmail("dept" + unique() + "@college.edu")
            .contactPhone("9876543210")
            .building("Block-T")
            .isArchived(false)
            .build();
        AcademicYear year = AcademicYear.builder().yearLabel("1st Year").isEnabled(true).build();
        year.addSection(Section.builder().name("A").studentStrength(60).status("ACTIVE").build());
        dept.addAcademicYear(year);
        return departmentRepository.save(dept);
    }

    private AcademicYear year(Department dept) {
        return dept.getAcademicYears().get(0);
    }

    private Section section(AcademicYear year) {
        return year.getSections().get(0);
    }

    private User newSuperAdmin() {
        Role role = roleRepository.findByName(RoleName.ROLE_SUPER_ADMIN)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name(RoleName.ROLE_SUPER_ADMIN)
                .description(RoleName.ROLE_SUPER_ADMIN.name())
                .build()));
        String username = "subjtype_admin_" + unique();
        User user = User.builder()
            .username(username)
            .email(username + "@college.edu")
            .password(passwordEncoder.encode(PASSWORD))
            .fullName("Subject Type Admin")
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

    private String subjectBody(String code, Department dept, AcademicYear year, Section section, String type) {
        return "{\"subjectCode\":\"" + code + "\",\"subjectName\":\"Typed Subject\","
            + "\"departmentId\":" + dept.getId() + ",\"academicYearId\":" + year.getId()
            + ",\"sectionId\":" + section.getId()
            + ",\"semester\":1,\"credits\":3,\"theoryHours\":3,\"practicalHours\":0,"
            + "\"subjectType\":\"" + type + "\"}";
    }

    private int create(String token, String body) throws Exception {
        return mockMvc.perform(post("/subjects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn().getResponse().getStatus();
    }

    @Test
    void subjectType_acceptsTheoryLabGameOther_andRejectsRetiredOrUnknown() throws Exception {
        String token = loginAccessToken(newSuperAdmin().getUsername());
        Department dept = newDepartment("SubjType CSE");
        AcademicYear y = year(dept);
        Section s = section(y);

        int i = 0;
        for (String type : new String[]{"THEORY", "LAB", "GAME", "OTHER"}) {
            String code = "TYPE" + (++i);
            assertEquals(201, create(token, subjectBody(code, dept, y, s, type)),
                "valid subject type must be accepted: " + type);
        }

        assertEquals(400, create(token, subjectBody("TYPE90", dept, y, s, "ELECTIVE")),
            "the retired ELECTIVE value must be rejected");
        assertEquals(400, create(token, subjectBody("TYPE91", dept, y, s, "MANDATORY")),
            "the retired MANDATORY value must be rejected");
        assertEquals(400, create(token, subjectBody("TYPE92", dept, y, s, "BOGUS")),
            "an unknown subject type must be rejected");
    }
}
