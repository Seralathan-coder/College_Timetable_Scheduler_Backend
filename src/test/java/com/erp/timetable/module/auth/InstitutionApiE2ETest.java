package com.erp.timetable.module.auth;

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
import com.erp.timetable.module.auth.entity.Institution;
import com.erp.timetable.module.auth.entity.Role;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.InstitutionRepository;
import com.erp.timetable.module.auth.repository.RoleRepository;
import com.erp.timetable.module.auth.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 — Institution API E2E tests.
 *
 * <p>Boots the real application on an ISOLATED in-memory H2 database
 * (unique {@code institution_e2e} name; never touches the file-based dev H2)
 * and performs REAL logins so the real JWT filter resolves a genuine
 * {@code UserPrincipal}.
 *
 * <p>Security surface verified here:
 * <ul>
 *   <li>{@code GET /institution} — any authenticated user.</li>
 *   <li>{@code PUT /institution} — {@code ROLE_SUPER_ADMIN} only; HOD/FACULTY
 *       and users without roles are rejected with 403 (server-side check).</li>
 *   <li>No institution id, ownership, or user id is ever read from the
 *       request — the feature operates only on the single global row (id = 1).</li>
 * </ul>
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:institution_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class InstitutionApiE2ETest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private InstitutionRepository institutionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String loginToken(String usernameOrEmail, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + usernameOrEmail + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(body.get("success").asBoolean(), "login should succeed: " + body);
        return body.get("data").get("accessToken").asText();
    }

    private User saveUserWithRole(String username, String email, RoleName roleName) {
        Role role = roleRepository.findByName(roleName)
            .orElseGet(() -> roleRepository.save(Role.builder().name(roleName).build()));
        User user = User.builder()
            .username(username)
            .email(email)
            .password(passwordEncoder.encode("Pass@1234"))
            .fullName("Test User " + username)
            .isActive(true)
            .build();
        user.addRole(role);
        return userRepository.save(user);
    }

    @Test
    void getInstitution_anyAuthenticatedUser_canRead() throws Exception {
        saveUserWithRole("instuser", "inst@college.edu", RoleName.ROLE_FACULTY);
        String token = loginToken("instuser", "Pass@1234");

        MvcResult result = mockMvc.perform(get("/institution")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertEquals(1L, data.get("id").asLong(), "the single global row is always id = 1");
        assertTrue(data.get("name").asText().length() > 0, "institution name present");
    }

    @Test
    void putInstitution_superAdmin_canUpdate() throws Exception {
        saveUserWithRole("sadmin", "sadmin@college.edu", RoleName.ROLE_SUPER_ADMIN);
        String token = loginToken("sadmin", "Pass@1234");

        MvcResult result = mockMvc.perform(put("/institution")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sona College of Technology\",\"address\":\"Salem - 636005\"}"))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertEquals("Sona College of Technology", data.get("name").asText());
        assertEquals("Salem - 636005", data.get("address").asText());

        Institution persisted = institutionRepository
            .findById(Institution.SINGLETON_ID).orElseThrow();
        assertEquals("Sona College of Technology", persisted.getName());
        assertEquals("Salem - 636005", persisted.getAddress());
    }

    @Test
    void putInstitution_nonSuperAdmin_rejectedWith403() throws Exception {
        saveUserWithRole("hod", "hod@college.edu", RoleName.ROLE_HOD);
        String token = loginToken("hod", "Pass@1234");

        mockMvc.perform(put("/institution")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hacked College\",\"address\":\"Nowhere\"}"))
            .andExpect(status().isForbidden())
            .andReturn();

        Institution persisted = institutionRepository
            .findById(Institution.SINGLETON_ID).orElseThrow();
        assertNotEquals("Hacked College", persisted.getName(),
            "a non-SUPER_ADMIN must NEVER modify the institution row");
    }

    @Test
    void getInstitution_institution_notAutoCreatedOnRead_doesNotBreak() throws Exception {
        // The row is seeded by DataInitializer (dev) / V9 migration (prod);
        // in this isolated DB the runner seeds it too. The API must serve it.
        saveUserWithRole("instreader", "reader@college.edu", RoleName.ROLE_FACULTY);
        String token = loginToken("instreader", "Pass@1234");

        mockMvc.perform(get("/institution")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    }

    @Test
    void profileIncludesInstitution_readOnly() throws Exception {
        saveUserWithRole("profi", "profi@college.edu", RoleName.ROLE_FACULTY);
        institutionRepository.save(Institution.builder()
            .id(Institution.SINGLETON_ID)
            .name("Xavier Engineering College")
            .address("42 Main Rd")
            .build());
        String token = loginToken("profi", "Pass@1234");

        MvcResult result = mockMvc.perform(get("/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertEquals("Xavier Engineering College", data.get("institutionName").asText());
        assertEquals("42 Main Rd", data.get("institutionAddress").asText());
    }

    @Test
    void profileCannotChangeInstitutionViaPutMe() throws Exception {
        saveUserWithRole("attacker", "attacker@college.edu", RoleName.ROLE_FACULTY);
        institutionRepository.save(Institution.builder()
            .id(Institution.SINGLETON_ID)
            .name("Original College")
            .address("Original St")
            .build());
        String token = loginToken("attacker", "Pass@1234");

        mockMvc.perform(put("/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Attacker Name\","
                    + "\"institutionName\":\"Captured College\","
                    + "\"institutionAddress\":\"Captured St\"}"))
            .andExpect(status().isOk());

        Institution persisted = institutionRepository
            .findById(Institution.SINGLETON_ID).orElseThrow();
        assertEquals("Original College", persisted.getName(),
            "PUT /me has no institution field — the DTO whitelist drops it");
        assertEquals("Original St", persisted.getAddress());
    }

    @Test
    void me_withoutToken_stillRejected() throws Exception {
        mockMvc.perform(get("/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void institution_withoutToken_rejected() throws Exception {
        mockMvc.perform(get("/institution"))
            .andExpect(status().isUnauthorized());
    }
}