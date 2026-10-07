package com.erp.timetable.module.auth;

import com.erp.timetable.module.auth.entity.College;
import com.erp.timetable.module.auth.entity.Role;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.CollegeRepository;
import com.erp.timetable.module.auth.repository.RoleRepository;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.DepartmentRepository;
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

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for the Forgot / Reset password flow.
 *
 * <p>No SMTP backend exists in this build, so the raw one-time token is handed
 * back in the response body ({@code data.resetToken}) for the calling device.
 * The server stores only its SHA-256 hash; tokens expire after 15 minutes, are
 * single-use, and a successful reset revokes every existing session so all
 * devices must sign in again with the new password.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:password_reset_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class PasswordResetE2ETest {

    private static final String PASSWORD = "Pass@1234";
    private static final String NEW_PASSWORD = "NewPass@999";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private CollegeRepository collegeRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final AtomicLong seq = new AtomicLong();

    // ── Helpers ────────────────────────────────────────────────────────

    private String unique() {
        return "U" + seq.incrementAndGet();
    }

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

    private College saveCollege(String name, String code) {
        return collegeRepository.save(College.builder()
            .name(name)
            .code(code)
            .address("Addr " + code)
            .phone("9876543210")
            .email(code.toLowerCase() + "@college.edu")
            .isActive(true)
            .build());
    }

    private User saveUser(String username, Department dept, College college) {
        Role role = roleRepository.findByName(RoleName.ROLE_FACULTY)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name(RoleName.ROLE_FACULTY)
                .description("FACULTY")
                .build()));
        User user = User.builder()
            .username(username)
            .email(username + "@college.edu")
            .password(passwordEncoder.encode(PASSWORD))
            .fullName(username)
            .isActive(true)
            .department(dept)
            .college(college)
            .build();
        user.addRole(role);
        return userRepository.saveAndFlush(user);
    }

    private String forgot(String identifier) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + identifier + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        return data.has("resetToken") && !data.get("resetToken").isNull()
            ? data.get("resetToken").asText() : null;
    }

    private MvcResult reset(String token, String newPassword) throws Exception {
        return mockMvc.perform(post("/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}"))
            .andReturn();
    }

    private int loginStatus(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andReturn();
        return result.getResponse().getStatus();
    }

    // ── Happy path ─────────────────────────────────────────────────────

    @Test
    void forgot_andReset_rotatesPassword_validatesNewPassword() throws Exception {
        Department dept = newDepartment("PWR Happy " + unique());
        User user = saveUser("pwr_happy_" + unique(), dept, null);

        String token = forgot(user.getUsername());
        assertTrue(token != null && !token.isBlank(), "single account must receive a token");

        mockMvc.perform(post("/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
            .andExpect(status().isOk());

        assertEquals(401, loginStatus(user.getUsername(), PASSWORD), "old password must die");
        assertEquals(200, loginStatus(user.getUsername(), NEW_PASSWORD), "new password must work");
    }

    @Test
    void reset_singleUse_tokenCannotBeReplayed() throws Exception {
        Department dept = newDepartment("PWR Once " + unique());
        User user = saveUser("pwr_once_" + unique(), dept, null);

        String token = forgot(user.getUsername());
        assertEquals(200, reset(token, NEW_PASSWORD).getResponse().getStatus());
        assertEquals(422, reset(token, "AnotherPass@1").getResponse().getStatus(),
            "replaying the same token must fail");
    }

    @Test
    void reset_invalidToken_isRejected() throws Exception {
        Department dept = newDepartment("PWR Invalid " + unique());
        User user = saveUser("pwr_invalid_" + unique(), dept, null);
        forgot(user.getUsername());

        assertEquals(422, reset("definitely-not-a-token", NEW_PASSWORD).getResponse().getStatus());
    }

    @Test
    void reset_expiredToken_isRejected() throws Exception {
        Department dept = newDepartment("PWR Expired " + unique());
        User user = saveUser("pwr_expired_" + unique(), dept, null);

        String token = forgot(user.getUsername());
        user.setPasswordResetExpiry(Instant.now().minusSeconds(60));
        userRepository.saveAndFlush(user);

        assertEquals(422, reset(token, NEW_PASSWORD).getResponse().getStatus());
        assertEquals(200, loginStatus(user.getUsername(), PASSWORD),
            "expired token must not change the password");
    }

    @Test
    void forgot_unknownIdentifier_returnsGenericSuccess_noToken() throws Exception {
        String token = forgot("no_such_user_" + unique() + "@nowhere.edu");
        assertNull(token, "unknown identifier must not reveal whether the account exists");
    }

    @Test
    void forgot_sharedIdentifier_acrossColleges_isRejected() throws Exception {
        College collegeA = saveCollege("Amb A " + unique(), "AMBA" + unique());
        College collegeB = saveCollege("Amb B " + unique(), "AMBB" + unique());
        String shared = "shared_login_" + unique();
        saveUser(shared, newDepartment("PWR AmbA " + unique()), collegeA);
        saveUser(shared, newDepartment("PWR AmbB " + unique()), collegeB);

        mockMvc.perform(post("/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + shared + "\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void reset_shortPassword_isRejectedByValidation() throws Exception {
        Department dept = newDepartment("PWR Short " + unique());
        User user = saveUser("pwr_short_" + unique(), dept, null);
        String token = forgot(user.getUsername());

        mockMvc.perform(post("/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"short\"}"))
            .andExpect(status().isBadRequest());
        assertEquals(200, loginStatus(user.getUsername(), PASSWORD),
            "invalid body must not alter the password");
    }

    @Test
    void reset_revokesExistingSessions_userMustReLogin() throws Exception {
        Department dept = newDepartment("PWR Sess " + unique());
        User user = saveUser("pwr_sess_" + unique(), dept, null);

        MvcResult login = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + user.getUsername() + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode token = objectMapper.readTree(login.getResponse().getContentAsString()).get("data");
        String accessToken = token.get("accessToken").asText();
        String refreshToken = token.get("refreshToken").asText();

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk());

        String resetToken = forgot(user.getUsername());
        assertEquals(200, reset(resetToken, NEW_PASSWORD).getResponse().getStatus());

        mockMvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isUnprocessableEntity());
    }
}