package com.erp.timetable.module.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.entity.UserSession;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.auth.repository.UserSessionRepository;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5 — Auth-flow integration tests (login / refresh / logout) + the new
 * multi-device session endpoints.
 *
 * <p>Follows the approved TEST-FIRST sequence. The original baseline (written
 * against the single-session model) is preserved in this class except for the
 * assertions that describe behavior DELIBERATELY changed by Phase 5:
 * <ul>
 *   <li>login now creates a {@code user_sessions} row instead of writing
 *       {@code users.refresh_token};</li>
 *   <li>refresh rotates the token inside the SAME session row;</li>
 *   <li>a second login creates a SECOND session instead of overwriting the
 *       first (multi-device);</li>
 *   <li>logout with a body refreshToken revokes only that device; without a
 *       body it revokes all sessions.</li>
 * </ul>
 * Every other assertion is unchanged from the green baseline.
 *
 * <p>Isolated in-memory H2 ({@code auth_flow_e2e}); real logins exercise the
 * genuine {@code JwtAuthenticationFilter}.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:auth_flow_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class AuthFlowE2ETest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    // ── Helpers ────────────────────────────────────────────────────────

    private User saveUser(String username, String email) {
        return userRepository.save(User.builder()
            .username(username)
            .email(email)
            .password(passwordEncoder.encode("Pass@1234"))
            .fullName("Test User " + username)
            .isActive(true)
            .build());
    }

    private JsonNode loginBody(String usernameOrEmail, String password) throws Exception {
        return loginBody(usernameOrEmail, password, null, null);
    }

    private JsonNode loginBody(String usernameOrEmail, String password,
                               String userAgent, String forwardedIp) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + usernameOrEmail + "\",\"password\":\"" + password + "\"}")
                .header("User-Agent", userAgent == null ? "TestDriver/1.0" : userAgent)
                .header("X-Forwarded-For", forwardedIp == null ? "127.0.0.1" : forwardedIp))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(body.get("success").asBoolean(), "login should succeed: " + body);
        return body.get("data");
    }

    private String loginRefreshToken(String usernameOrEmail) throws Exception {
        return loginBody(usernameOrEmail, "Pass@1234").get("refreshToken").asText();
    }

    private String loginRefreshToken(String usernameOrEmail, String userAgent) throws Exception {
        return loginBody(usernameOrEmail, "Pass@1234", userAgent, null).get("refreshToken").asText();
    }

    private String loginAccessToken(String usernameOrEmail) throws Exception {
        return loginBody(usernameOrEmail, "Pass@1234").get("accessToken").asText();
    }

    private String doRefresh(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(body.get("success").asBoolean(), "refresh should succeed: " + body);
        return body.get("data").get("refreshToken").asText();
    }

    private void expectRefreshRejected(String refreshToken) throws Exception {
        mockMvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andReturn();
    }

    // ── Login ──────────────────────────────────────────────────────────

    /** Phase 5 change: login creates a user_sessions row; users.refresh_token is no longer written. */
    @Test
    void login_returnsTokenPair_andCreatesSessionRow() throws Exception {
        User u = saveUser("sessa", "sessa@college.edu");

        JsonNode data = loginBody("sessa", "Pass@1234", "Chrome-SessionA", "203.0.113.10");

        assertNotNull(data.get("accessToken").asText());
        assertTrue(data.get("accessToken").asText().length() > 20);
        assertNotNull(data.get("refreshToken").asText());
        assertEquals("Bearer", data.get("tokenType").asText());

        User persisted = userRepository.findById(u.getId()).orElseThrow();
        assertNull(persisted.getRefreshToken(),
            "Phase 5: users.refresh_token column is no longer written by login");

        UserSession session = userSessionRepository.findByRefreshToken(
            data.get("refreshToken").asText()).orElseThrow();
        assertEquals(u.getId(), session.getUser().getId());
        assertFalse(Boolean.TRUE.equals(session.getIsRevoked()), "fresh session must not be revoked");
        assertNotNull(session.getExpiresAt());
        assertEquals("Chrome-SessionA", session.getDeviceInfo());
        assertEquals("203.0.113.10", session.getIpAddress());
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        saveUser("sessb", "sessb@college.edu");
        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"sessb\",\"password\":\"WrongPass@1\"}"))
            .andExpect(status().isUnauthorized())
            .andReturn();
    }

    @Test
    void login_unknownUser_returns401() throws Exception {
        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"nobody\",\"password\":\"Pass@1234\"}"))
            .andExpect(status().isUnauthorized())
            .andReturn();
    }

    // ── Refresh ────────────────────────────────────────────────────────

    /** Phase 5 change: rotation happens inside the SAME session row (device identity kept). */
    @Test
    void refresh_rotatesRefreshToken_onTheSameSession() throws Exception {
        saveUser("sessc", "sessc@college.edu");
        String rt1 = loginRefreshToken("sessc");

        String rt2 = doRefresh(rt1);

        assertNotEquals(rt1, rt2, "refresh must rotate the refresh token");

        UserSession session = userSessionRepository.findByRefreshToken(rt2).orElseThrow();
        assertEquals(rt2, session.getRefreshToken(),
            "the same session row must now hold the rotated token");
        assertNotNull(session.getExpiresAt());
        assertEquals(1, userSessionRepository.findByUserId(session.getUser().getId()).size(),
            "rotation must NOT create a new session row — the device keeps its session");
        expectRefreshRejected(rt1);
    }

    @Test
    void refresh_withUnknownToken_returns422() throws Exception {
        saveUser("sessd", "sessd@college.edu");
        expectRefreshRejected(UUID.randomUUID().toString());
    }

    /** Expiry now lives on the session row (Phase 5). Contract unchanged: 422 + expired message. */
    @Test
    void refresh_withExpiredSession_isRejected() throws Exception {
        saveUser("sesse", "sesse@college.edu");
        String rt1 = loginRefreshToken("sesse");

        UserSession session = userSessionRepository.findByRefreshToken(rt1).orElseThrow();
        session.setExpiresAt(Instant.now().minusSeconds(60));
        userSessionRepository.save(session);

        mockMvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + rt1 + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(result -> {
                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                assertFalse(body.get("success").asBoolean());
                assertEquals("Refresh token has expired. Please login again.",
                    body.get("message").asText());
            })
            .andReturn();
    }

    /**
     * THE core Phase 5 behavior change: a second login now creates a SECOND
     * session instead of overwriting the first. Asserted against the explicit
     * baseline test {@code secondLogin_overwritesFirstRefreshToken}.
     */
    @Test
    void secondLogin_keepsFirstRefreshToken() throws Exception {
        saveUser("sessf", "sessf@college.edu");
        String rt1 = loginRefreshToken("sessf");
        String rt2 = loginRefreshToken("sessf");

        assertNotEquals(rt1, rt2);

        List<UserSession> sessions = userSessionRepository.findByUserId(
            userSessionRepository.findByRefreshToken(rt1).orElseThrow().getUser().getId());
        assertEquals(2, sessions.size(),
            "two logins must produce two session rows");
        assertTrue(sessions.stream().anyMatch(s -> s.getRefreshToken().equals(rt1)));
        assertTrue(sessions.stream().anyMatch(s -> s.getRefreshToken().equals(rt2)));

        // Neither device's token is dead — the single-session overwrite is gone.
        assertNotNull(doRefresh(rt1), "first device's refresh token stays valid");
        assertNotNull(doRefresh(rt2), "second device's refresh token stays valid");
    }

    // ── Logout ─────────────────────────────────────────────────────────

    /** Phase 5 change: logout WITHOUT a body revokes ALL of the user's sessions. */
    @Test
    void logout_withoutBody_revokesAllSessions() throws Exception {
        saveUser("sessg", "sessg@college.edu");
        String rt1 = loginRefreshToken("sessg");
        String rt2 = loginRefreshToken("sessg");
        String access = loginAccessToken("sessg");

        mockMvc.perform(post("/auth/logout")
                .header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andReturn();

        expectRefreshRejected(rt1);
        expectRefreshRejected(rt2);
        userSessionRepository.findByRefreshToken(rt1).ifPresent(s ->
            assertTrue(Boolean.TRUE.equals(s.getIsRevoked()), "session 1 must be revoked"));
        userSessionRepository.findByRefreshToken(rt2).ifPresent(s ->
            assertTrue(Boolean.TRUE.equals(s.getIsRevoked()), "session 2 must be revoked"));
    }

    /** Phase 5 new: logout WITH a refresh token in the body revokes only that device. */
    @Test
    void logout_withBodyToken_revokesOnlyThatSession() throws Exception {
        saveUser("sessh", "sessh@college.edu");
        String rt1 = loginRefreshToken("sessh");
        String rt2 = loginRefreshToken("sessh");
        String access = loginAccessToken("sessh");

        mockMvc.perform(post("/auth/logout")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + rt1 + "\"}"))
            .andExpect(status().isOk())
            .andReturn();

        expectRefreshRejected(rt1);
        assertNotNull(doRefresh(rt2), "the other device's session must survive a device-scoped logout");
    }

    @Test
    void logout_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/auth/logout"))
            .andExpect(status().isUnauthorized())
            .andReturn();
    }

    // ── Sessions endpoints (Phase 5) ───────────────────────────────────

    @Test
    void getMeSessions_listsOnlyActiveSessions_withDeviceAndIp() throws Exception {
        saveUser("sessi", "sessi@college.edu");
        String access = loginAccessToken("sessi");
        loginRefreshToken("sessi", "Chrome-SessionA");
        loginRefreshToken("sessi", "Firefox-SessionB");

        MvcResult result = mockMvc.perform(get("/me/sessions")
                .header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertEquals(3, data.size(), "three logins → three active sessions listed");
        for (JsonNode s : data) {
            assertTrue(s.get("active").asBoolean());
            assertNotNull(s.get("sessionId"));
        }
        List<String> devices = data.findValuesAsText("device");
        assertTrue(devices.contains("Chrome-SessionA"), "device A must appear, was: " + devices);
        assertTrue(devices.contains("Firefox-SessionB"), "device B must appear, was: " + devices);
        assertTrue(data.findValuesAsText("ipAddress").contains("127.0.0.1"));
    }

    @Test
    void getMeSessions_neverExposesRefreshTokens() throws Exception {
        saveUser("sessj", "sessj@college.edu");
        String access = loginAccessToken("sessj");

        String body = mockMvc.perform(get("/me/sessions")
                .header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("refreshToken") || body.contains("refresh_token"),
            "the session API must never serialize a refresh token: " + body);
    }

    @Test
    void deleteMeSession_revokesOwnedSession_onlyOwn() throws Exception {
        saveUser("sessk", "sessk@college.edu");
        String rtA = loginRefreshToken("sessk");
        String rtB = loginRefreshToken("sessk");
        String access = loginAccessToken("sessk");

        UserSession sessionA = userSessionRepository.findByRefreshToken(rtA).orElseThrow();

        mockMvc.perform(delete("/me/sessions/" + sessionA.getId())
                .header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andReturn();

        expectRefreshRejected(rtA);
        assertNotNull(doRefresh(rtB), "revoking session A must NOT touch session B");
        assertTrue(Boolean.TRUE.equals(
            userSessionRepository.findByRefreshToken(rtA).orElseThrow().getIsRevoked()));
    }

    @Test
    void deleteMeSession_unknownOrUnownedSection_returns404() throws Exception {
        // Unowned: another user's session cannot be revoked by someone else.
        saveUser("sessm", "sessm@college.edu");
        saveUser("sessn", "sessn@college.edu");
        Long otherUsersSessionId = userSessionRepository.findByRefreshToken(
            loginRefreshToken("sessn")).orElseThrow().getId();
        String access = loginAccessToken("sessm");

        mockMvc.perform(delete("/me/sessions/" + otherUsersSessionId)
                .header("Authorization", "Bearer " + access))
            .andExpect(status().isNotFound())
            .andReturn();

        // Unknown id also 404.
        mockMvc.perform(delete("/me/sessions/999999")
                .header("Authorization", "Bearer " + access))
            .andExpect(status().isNotFound())
            .andReturn();
    }

    @Test
    void deleteMeSessionsAll_withCurrentToken_keepsCurrentDevice() throws Exception {
        saveUser("sesso", "sesso@college.edu");
        String rtA = loginRefreshToken("sesso");
        String rtB = loginRefreshToken("sesso");
        String access = loginAccessToken("sesso");

        mockMvc.perform(delete("/me/sessions/all")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + rtA + "\"}"))
            .andExpect(status().isOk())
            .andReturn();

        assertNotNull(doRefresh(rtA), "the token named in the body is the current device → kept");
        expectRefreshRejected(rtB);
    }

    @Test
    void deleteMeSessionsAll_withoutBody_revokesAll() throws Exception {
        saveUser("sessp", "sessp@college.edu");
        String rtA = loginRefreshToken("sessp");
        String rtB = loginRefreshToken("sessp");
        String access = loginAccessToken("sessp");

        mockMvc.perform(delete("/me/sessions/all")
                .header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andReturn();

        expectRefreshRejected(rtA);
        expectRefreshRejected(rtB);
    }

    @Test
    void getMe_reportsActiveSessionCount() throws Exception {
        saveUser("sessq", "sessq@college.edu");
        String access = loginAccessToken("sessq");
        loginRefreshToken("sessq");
        loginRefreshToken("sessq");

        MvcResult result = mockMvc.perform(get("/me")
                .header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andReturn();
        assertEquals(3L, objectMapper.readTree(result.getResponse().getContentAsString())
            .get("data").get("activeSessionCount").asLong(),
            "activeSessionCount must reflect the real number of active sessions (3 logins)");

        mockMvc.perform(delete("/me/sessions/all")
                .header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andReturn();

        MvcResult after = mockMvc.perform(get("/me")
                .header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andReturn();
        assertEquals(0L, objectMapper.readTree(after.getResponse().getContentAsString())
            .get("data").get("activeSessionCount").asLong(),
            "revoking all sessions must drop activeSessionCount to 0");
    }

    @Test
    void getMeSessions_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/me/sessions"))
            .andExpect(status().isUnauthorized())
            .andReturn();
    }
}