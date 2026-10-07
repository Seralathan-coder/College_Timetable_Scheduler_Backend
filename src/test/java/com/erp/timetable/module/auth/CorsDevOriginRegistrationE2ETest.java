package com.erp.timetable.module.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for the HTTP 403 that public college registration
 * returned when the Vite dev server was NOT on its configured port.
 *
 * <p>Background: Vite pins {@code server.port = 5173} in {@code vite.config.ts}
 * but silently falls through to 5174, 5175, ... whenever 5173 is already in use.
 * Spring Security evaluates the CORS allowlist BEFORE the controller runs and
 * answers an unlisted {@code Origin} with {@code 403}, so the perfectly valid,
 * public, JWT-free {@code POST /auth/register-college} failed - but ONLY on a
 * shifted port, which made it look intermittent.
 *
 * <p>These tests deliberately boot the real {@code h2} profile so they exercise
 * the actual {@code app.cors.allowed-origins} value in
 * {@code application-h2.yml} rather than a value hardcoded in the test, which is
 * what made the gap possible in the first place. Only the datasource is
 * overridden (to an in-memory H2) so the suite never touches the persistent
 * developer database.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:cors_dev_origin_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class CorsDevOriginRegistrationE2ETest {

    private static final String PW = "Pass@1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("registration from a SHIFTED Vite port (5174) is accepted, not CORS-rejected")
    void registration_fromShiftedDevPort_isNotRejectedByCors() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register-college")
                        // no Authorization header: this endpoint is public
                        .header("Origin", "http://localhost:5174")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Shifted Port", "SHIFT01", "shift01")))
                // the bug produced 403 here
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode data = data(result);
        assertEquals("shift01", data.get("adminUsername").asText());
        assertEquals("SHIFT01", data.get("code").asText());
    }

    @Test
    @DisplayName("any localhost dev port is accepted, so a further Vite fallback cannot re-break it")
    void registration_fromArbitraryLocalhostDevPort_isAccepted() throws Exception {
        for (String port : new String[]{"5173", "5175", "5273", "3000"}) {
            mockMvc.perform(post("/auth/register-college")
                            .header("Origin", "http://localhost:" + port)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("Port " + port, "PORT" + port, "port" + port)))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    @DisplayName("the loopback 127.0.0.1 alias is accepted too")
    void registration_fromLoopbackAlias_isAccepted() throws Exception {
        mockMvc.perform(post("/auth/register-college")
                        .header("Origin", "http://127.0.0.1:5174")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Loopback", "LOOPB01", "loopb01")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a foreign origin is still refused by CORS, so the wildcard is not a blanket allow")
    void foreignOrigin_isStillRefused() throws Exception {
        mockMvc.perform(post("/auth/register-college")
                        .header("Origin", "https://evil.example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Evil", "EVIL001", "evil001")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("browser preflight for registration from a shifted dev port is answered")
    void preflightFromShiftedDevPort_isHandled() throws Exception {
        mockMvc.perform(options("/auth/register-college")
                        .header("Origin", "http://localhost:5174")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(result -> assertTrue(
                        String.valueOf(result.getResponse().getHeader("Access-Control-Allow-Origin"))
                                .contains("localhost:5174"),
                        "preflight must echo the allowed origin"));
    }

    @Test
    @DisplayName("the port wildcard does not weaken authentication on protected endpoints")
    void protectedEndpoints_stillRejectAnonymousCallers() throws Exception {
        for (String path : new String[]{"/auth/me", "/departments", "/colleges", "/faculty", "/subjects", "/timetable/my"}) {
            mockMvc.perform(get(path)
                            .header("Origin", "http://localhost:5174")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("validation still rejects bad registration bodies over a shifted dev port")
    void invalidRegistration_overShiftedPort_isStillValidated() throws Exception {
        // password mismatch -> 422
        mockMvc.perform(post("/auth/register-college")
                        .header("Origin", "http://localhost:5174")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mismatch\",\"code\":\"MIS001\",\"collegeId\":\"mis001\",\"password\":\""
                                + PW + "\",\"confirmPassword\":\"Different@1\"}"))
                .andExpect(status().isUnprocessableEntity());

        // blank code -> 400
        mockMvc.perform(post("/auth/register-college")
                        .header("Origin", "http://localhost:5174")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blank\",\"code\":\"\",\"collegeId\":\"blank01\",\"password\":\""
                                + PW + "\",\"confirmPassword\":\"" + PW + "\"}"))
                .andExpect(status().isBadRequest());
    }

    private String body(String name, String code, String loginId) {
        return "{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"email\":\"info@" + code.toLowerCase()
                + ".edu\",\"collegeId\":\"" + loginId + "\",\"password\":\"" + PW
                + "\",\"confirmPassword\":\"" + PW + "\"}";
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
