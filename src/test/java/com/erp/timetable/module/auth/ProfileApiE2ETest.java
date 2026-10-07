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
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.auth.repository.UserSessionRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 — Profile API E2E regression tests.
 *
 * <p>Boots the real application (MVC + security + JPA) on an ISOLATED in-memory
 * H2 database (unique {@code profile_e2e} name; never touches the file-based
 * dev H2). Performs a REAL login so the real {@code JwtAuthenticationFilter}
 * resolves a genuine {@code UserPrincipal} — this exercises the actual path by
 * which the server derives identity from the token, not a mocked principal.
 *
 * <p>Security surface verified here:
 * <ul>
 *   <li>{@code GET /me} and {@code PUT /me} accept NO user ID from the client
 *       (no path/query/body identity); identity comes only from the Bearer token.</li>
 *   <li>{@code PUT /me} whitelist: only fullName/phone/profilePhotoUrl are
 *       writable. role, username, email, isActive, departmentId/departmentName,
 *       employeeId, designation, password sent in a body are silently ignored
 *       (Jackson unknown-property tolerance) and therefore cannot be changed.</li>
 *   <li>Reading one user's profile with their token returns exactly that user —
 *       there is no field anywhere in the request that could target another.</li>
 * </ul>
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:profile_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class ProfileApiE2ETest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
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

    private User saveUser(String username, String email) {
        return userRepository.save(User.builder()
            .username(username)
            .email(email)
            .password(passwordEncoder.encode("Pass@1234"))
            .fullName("Test User " + username)
            .isActive(true)
            .build());
    }

    @Test
    void getMe_returnsOwnProfileDerivedFromTokenOnly() throws Exception {
        saveUser("profa", "profa@college.edu");

        String token = loginToken("profa", "Pass@1234");

        // No user ID anywhere in the request — only the Bearer token.
        MvcResult result = mockMvc.perform(get("/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertEquals("profa", data.get("username").asText());
        assertEquals("profa@college.edu", data.get("email").asText());
        assertEquals("Test User profa", data.get("fullName").asText());
        assertTrue(data.get("isActive").asBoolean());
        assertNotNull(data.get("joinedDate"));
        assertTrue(data.get("roles").isArray());
        assertTrue(data.get("activeSessionCount").asLong() >= 1,
            "one login created one active user_sessions row (Phase 5)");
        // Global Jackson `default-property-inclusion: non_null` omits nulls,
        // so employeeId/designation may be absent OR explicit null. Both are
        // "no Faculty record" and equivalent to the frontend's "N/A" mapping.
        JsonNode employeeId = data.get("employeeId");
        JsonNode designation = data.get("designation");
        assertTrue(employeeId == null || employeeId.isNull(), "no Faculty record → employeeId null/absent, was: "
            + employeeId);
        assertTrue(designation == null || designation.isNull(), "no Faculty record → designation null/absent, was: "
            + designation);
        // profile response must NEVER leak password (absent or null both OK)
        assertTrue(data.get("password") == null,
            "profile response must NEVER leak password, got: " + data.get("password"));
    }

    @Test
    void getMe_withOneUsersToken_neverReturnsAnotherUser() throws Exception {
        saveUser("profa", "profa@college.edu");
        saveUser("profb", "profb@college.edu");

        String tokenA = loginToken("profa", "Pass@1234");

        MvcResult result = mockMvc.perform(get("/me")
                .header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertEquals("profa", data.get("username").asText(), "token for A must always resolve to A");
        assertEquals("profa@college.edu", data.get("email").asText());
    }

    @Test
    void putMe_updatesOnlyWhitelistedFields() throws Exception {
        saveUser("profa", "profa@college.edu");
        String token = loginToken("profa", "Pass@1234");
        String bearer = "Bearer " + token;

        MvcResult putResult = mockMvc.perform(put("/me")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"
                    + "\"fullName\":\"Renamed User\","
                    + "\"phone\":\"+91 90000 11111\","
                    + "\"profilePhotoUrl\":\"/uploads/photos/x.png\""
                    + "}"))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode updated = objectMapper.readTree(putResult.getResponse().getContentAsString()).get("data");
        assertEquals("Renamed User", updated.get("fullName").asText());
        assertEquals("+91 90000 11111", updated.get("phone").asText());
        // Phase 6 graceful fallback: /uploads/photos/x.png has no file on disk, so the
        // response omits profilePhotoUrl (letter-avatar degrade); the DB still holds it.
        JsonNode echoedPhoto = updated.get("profilePhotoUrl");
        assertTrue(echoedPhoto == null || echoedPhoto.isNull(),
            "non-existent photo file must degrade to letter-avatar in the response, was: " + echoedPhoto);

        // Persisted, not just echoed.
        User persisted = userRepository.findById(updated.get("userId").asLong()).orElseThrow();
        assertEquals("Renamed User", persisted.getFullName());
        assertEquals("+91 90000 11111", persisted.getPhone());
        assertEquals("/uploads/photos/x.png", persisted.getProfilePhotoUrl());
    }

    @Test
    void putMe_rejectsPrivilegeEscalationAttempts() throws Exception {
        // Seed a low-privilege user: no ROLE_SUPER_ADMIN, active.
        User u = saveUser("profa", "profa@college.edu");
        String token = loginToken("profa", "Pass@1234");
        String legitRefreshToken = objectMapper.readTree(
            mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"profa\",\"password\":\"Pass@1234\"}"))
                .andReturn().getResponse().getContentAsString())
            .get("data").get("refreshToken").asText();

        // Attack payload: tries to escalate role, deactivate account, change
        // department and identity. None of these fields exist on the whitelist DTO.
        String attackBody = "{"
            + "\"fullName\":\"Still Same Name\","
            + "\"roles\":[\"ROLE_SUPER_ADMIN\"],"
            + "\"isActive\":false,"
            + "\"departmentId\":999,"
            + "\"departmentName\":\"Hacked Dept\","
            + "\"username\":\"hacked_username\","
            + "\"email\":\"hacked@evil.com\","
            + "\"employeeId\":\"EVP-999\","
            + "\"designation\":\"President\","
            + "\"password\":\"Compromised@1\","
            + "\"refreshToken\":\"stolen\""
            + "}";

        mockMvc.perform(put("/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(attackBody))
            .andExpect(status().isOk())
            .andReturn();

        // Fully reload from DB — the ground truth the next login would see.
        User persisted = userRepository.findById(u.getId()).orElseThrow();
        assertFalse(persisted.getRoles().stream()
            .anyMatch(r -> r.getName().name().equals("ROLE_SUPER_ADMIN")),
            "roles must NOT be settable via PUT /me");
        assertTrue(persisted.getIsActive(), "isActive must NOT be settable via PUT /me");
        assertNull(persisted.getDepartment(), "department must NOT be settable via PUT /me");
        assertEquals("profa", persisted.getUsername(), "username must NOT be settable via PUT /me");
        assertEquals("profa@college.edu", persisted.getEmail(), "email must NOT be settable via PUT /me");
        assertNull(persisted.getRefreshToken(),
            "Phase 5: users.refresh_token is no longer written — refresh tokens live in user_sessions");
        assertTrue(userSessionRepository.findByRefreshToken(legitRefreshToken).isPresent(),
            "the attack body's refreshToken must NOT displace the legit login-created session");
        assertTrue(passwordEncoder.matches("Pass@1234", persisted.getPassword()),
            "password must NOT be changeable via PUT /me (change-password is its own endpoint)");
        // Whitelisted field in the same attack payload DID apply.
        assertEquals("Still Same Name", persisted.getFullName());
    }

    @Test
    void putMe_withoutFullName_isRejectedByValidation() throws Exception {
        saveUser("profa", "profa@college.edu");
        String token = loginToken("profa", "Pass@1234");

        mockMvc.perform(put("/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"+91 12345 67890\"}"))
            .andExpect(status().is4xxClientError())
            .andReturn();
    }

    @Test
    void me_withoutToken_isRejected() throws Exception {
        mockMvc.perform(get("/me"))
            .andExpect(status().isUnauthorized())
            .andReturn();
    }

    // ── Phase 4: Change Password ────────────────────────────────────

    @Test
    void postChangePassword_withCorrectCurrentPassword_changesHashAndInvalidatesOldLogin() throws Exception {
        User u = saveUser("pwda", "pwda@college.edu");
        String token = loginToken("pwda", "Pass@1234");

        mockMvc.perform(post("/me/change-password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Pass@1234\",\"newPassword\":\"NewPass@5678\"}"))
            .andExpect(status().isOk())
            .andReturn();

        // Persisted, not just echoed — hash now matches the new password.
        User persisted = userRepository.findById(u.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches("NewPass@5678", persisted.getPassword()),
            "stored hash must match the NEW password");
        assertFalse(passwordEncoder.matches("Pass@1234", persisted.getPassword()),
            "stored hash must NOT match the old password");

        // New password works on the next login.
        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"pwda\",\"password\":\"NewPass@5678\"}"))
            .andExpect(status().isOk())
            .andReturn();

        // The OLD password no longer works.
        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"pwda\",\"password\":\"Pass@1234\"}"))
            .andExpect(status().isUnauthorized())
            .andReturn();
    }

    @Test
    void postChangePassword_withWrongCurrentPassword_isRejectedAndHashUnchanged() throws Exception {
        User u = saveUser("pwdb", "pwdb@college.edu");
        String token = loginToken("pwdb", "Pass@1234");

        // Wrong current password → rejected (BusinessException → 422).
        mockMvc.perform(post("/me/change-password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"WrongOld@999\",\"newPassword\":\"NewPass@5678\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(result -> {
                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                assertFalse(body.get("success").asBoolean());
                assertEquals("Current password is incorrect", body.get("message").asText());
            })
            .andReturn();

        // Hash untouched — original password still valid.
        User persisted = userRepository.findById(u.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches("Pass@1234", persisted.getPassword()),
            "hash must remain the original one after a rejected change");
        assertFalse(passwordEncoder.matches("NewPass@5678", persisted.getPassword()));

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"pwdb\",\"password\":\"Pass@1234\"}"))
            .andExpect(status().isOk())
            .andReturn();
    }

    @Test
    void postChangePassword_withoutToken_isRejected() throws Exception {
        mockMvc.perform(post("/me/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Pass@1234\",\"newPassword\":\"NewPass@5678\"}"))
            .andExpect(status().isUnauthorized())
            .andReturn();
    }

    @Test
    void postChangePassword_invalidBody_isRejectedByValidation() throws Exception {
        User u = saveUser("pwdc", "pwdc@college.edu");
        String token = loginToken("pwdc", "Pass@1234");
        String bearer = "Bearer " + token;

        // Missing current password.
        mockMvc.perform(post("/me/change-password")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"NewPass@5678\"}"))
            .andExpect(status().isBadRequest())
            .andReturn();

        // New password too short (< 8 chars).
        mockMvc.perform(post("/me/change-password")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Pass@1234\",\"newPassword\":\"short\"}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    }
}