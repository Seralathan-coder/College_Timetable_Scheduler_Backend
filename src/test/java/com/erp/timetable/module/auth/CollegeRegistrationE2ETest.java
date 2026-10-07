package com.erp.timetable.module.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E for the PUBLIC first-time College registration flow
 * (POST /auth/register-college). A new college + its first College Admin
 * account are created WITHOUT any existing account, atomically; the admin is
 * tenant-scoped and existing HOD/Faculty flows still work.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:college_registration_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class CollegeRegistrationE2ETest {

    private static final String PW = "Pass@1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private final AtomicLong seq = new AtomicLong();

    private String unique(String prefix) {
        return prefix + seq.incrementAndGet();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json(result).get("data");
    }

    private String loginToken(String username, String password) throws Exception {
        String body = "{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + password + "\"}";
        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        return data(result).get("accessToken").asText();
    }

    private JsonNode loginOk(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return data(result);
    }

    private String registerBody(String name, String code, String loginId, String password, String confirmPassword) {
        return "{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"email\":\"info@" + code.toLowerCase()
            + ".edu\",\"collegeId\":\"" + loginId + "\",\"password\":\"" + password
            + "\",\"confirmPassword\":\"" + confirmPassword + "\"}";
    }

    /**
     * Registers a new college through the PUBLIC endpoint (NO Authorization
     * header, no pre-existing account) and returns the new College Admin's
     * login token. The admin login uses ONLY Login ID + password.
     */
    private String[] registerCollegePublic(String name, String code, String loginId) throws Exception {
        return registerCollegePublic(name, code, loginId, PW);
    }

    private String[] registerCollegePublic(String name, String code, String loginId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register-college")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(name, code, loginId, password, password)))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode registered = data(result);
        assertEquals(loginId, registered.get("adminUsername").asText());
        assertEquals(code, registered.get("code").asText());
        String token = loginToken(loginId, password);
        return new String[]{registered.get("id").asText(), token};
    }

    private long createDepartment(String token, String name, String hodUser, String hodPass) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"building\":\"Block-"
            + unique("B") + "\",\"contactEmail\":\"" + unique("dept") + "@college.edu\",\"contactPhone\":\"9876543210\"";
        if (hodUser != null) {
            body += ",\"hodUsername\":\"" + hodUser + "\",\"hodPassword\":\"" + hodPass + "\"";
        }
        body += "}";
        MvcResult result = mockMvc.perform(post("/departments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        return data(result).get("id").asLong();
    }

    private boolean listContains(MvcResult result, String field, String value) throws Exception {
        JsonNode arr = data(result).get("content");
        for (JsonNode item : arr) {
            if (value.equals(item.get(field).asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void publicRegistration_createsCollegeAndAdmin_atomically_andIsolated() throws Exception {
        // 1. PUBLIC registration — no Authorization header, no existing account.
        String nameA = unique("Reg Alpha");
        String codeA = unique("RA");
        String loginA = unique("ra_admin");
        String[] created = registerCollegePublic(nameA, codeA, loginA);
        String collegeA = created[0];
        String adminA = created[1];

        // 2. The account logs in through the EXISTING login flow and the JWT
        //    carries ROLE_COLLEGE_ADMIN.
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + loginA + "\",\"password\":\"" + PW + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode login = data(loginResult);
        assertTrue(login.get("roles").toString().contains("ROLE_COLLEGE_ADMIN"),
            "registered admin must hold ROLE_COLLEGE_ADMIN");

        // 3. The admin is tenant-scoped to their OWN college: can manage own
        //    departments (list + create + read by id).
        long deptA = createDepartment(adminA, "R-CSE", null, null);
        assertTrue(listContains(mockMvc.perform(get("/departments")
            .header("Authorization", "Bearer " + adminA)).andReturn(), "name", "R-CSE"),
            "registered admin must see their own college's department");
        mockMvc.perform(get("/departments/" + deptA).header("Authorization", "Bearer " + adminA))
            .andExpect(status().isOk());

        // 4. A SECOND college registers independently; tenant isolation holds
        //    in every direction (403).
        String nameB = unique("Reg Beta");
        String codeB = unique("RB");
        String loginB = unique("rb_admin");
        registerCollegePublic(nameB, codeB, loginB);
        String adminB = loginToken(loginB, PW);

        mockMvc.perform(get("/departments/" + deptA).header("Authorization", "Bearer " + adminB))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/departments/" + deptA)
                .header("Authorization", "Bearer " + adminB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hijacked\",\"building\":\"Block-Y\","
                    + "\"years\":[{\"yearLabel\":\"1st Year\",\"sections\":[\"A\"]}]}"))
            .andExpect(status().isForbidden());

        // 5. Existing HOD flow still works from a registered college (HOD login + own dept).
        String hodUser = unique("ra_hod");
        long deptA2 = createDepartment(adminA, "R-ECE", hodUser, PW);
        String hodToken = loginToken(hodUser, PW);
        assertTrue(listContains(mockMvc.perform(get("/departments")
            .header("Authorization", "Bearer " + hodToken)).andReturn(), "name", "R-ECE"),
            "HOD created under a registered college must still log in and see its dept");
        mockMvc.perform(get("/departments/" + deptA2).header("Authorization", "Bearer " + hodToken))
            .andExpect(status().isOk());
    }

    @Test
    void duplicateCollegeCode_isRejected_butSharedLoginId_isScopedPerCollege() throws Exception {
        String codeA = unique("RDUP");
        String login = unique("rdup_admin");
        String pwA = PW;
        String pwB = "Pass@9999";

        // 1. First registration succeeds.
        String[] createdA = registerCollegePublic(unique("Dup One"), codeA, login, pwA);
        String collegeA = createdA[0];

        // 2. Same college code, different admin login → 422 (college code stays globally unique).
        mockMvc.perform(post("/auth/register-college")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(unique("Dup Two"), codeA, unique("rd_other"), pwA, pwA)))
            .andExpect(status().isUnprocessableEntity());

        // 3. The SAME admin Login ID in a DIFFERENT college is now ALLOWED —
        //    login identifiers are unique per college, not globally. Each
        //    account keeps its OWN password (the password is the disambiguator).
        String codeB = unique("RDUP2");
        String[] createdB = registerCollegePublic(unique("Dup Three"), codeB, login, pwB);

        // 4/5. Login uses ONLY Login ID + password (NO College Code). Each
        //    password resolves to ITS OWN college.
        JsonNode loginA = loginOk(login, pwA);
        assertEquals(collegeA, loginA.get("collegeId").asText(),
            "College A's password must resolve the admin to College A");
        JsonNode loginB = loginOk(login, pwB);
        assertEquals(createdB[0], loginB.get("collegeId").asText(),
            "College B's password must resolve the admin to College B");

        // 6. Wrong password → 401; nothing reveals the Login ID is duplicated.
        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + login + "\",\"password\":\"WrongPass\"}"))
            .andExpect(status().isUnauthorized());

        // 7. SAME-college duplicate Login ID is rejected by the dedicated
        //    "Create College Admin Account" endpoint → 422.
        mockMvc.perform(post("/colleges/" + collegeA + "/admin-account")
                .header("Authorization", "Bearer " + loginToken("admin", "Admin@1234"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + login + "\",\"password\":\"" + pwA + "\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void passwordConfirmation_andValidation_areEnforced() throws Exception {
        String base = unique("RV");

        // 1. Password / confirmPassword mismatch → 422 before any creation.
        mockMvc.perform(post("/auth/register-college")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(unique("Val One"), base, unique("rv_admin"), PW, "Different@1")))
            .andExpect(status().isUnprocessableEntity());

        // 2. Blank required fields fail bean validation → 400.
        mockMvc.perform(post("/auth/register-college")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"code\":\"" + base + "\",\"password\":\"" + PW
                    + "\",\"confirmPassword\":\"" + PW + "\"}"))
            .andExpect(status().isBadRequest());

        // 3. Short / blank password → 400.
        mockMvc.perform(post("/auth/register-college")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + unique("Val Two") + "\",\"code\":\"" + unique("RV2")
                    + "\",\"collegeId\":\"" + unique("rv2") + "\",\"password\":\"\",\"confirmPassword\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void failedRegistration_leavesNoPartialData() throws Exception {
        // 1. Register college A successfully.
        String loginA = unique("rpa_admin");
        String codeA = unique("RPA");
        registerCollegePublic(unique("Partial One"), codeA, loginA);

        // 2. Attempt a registration that FAILS BEFORE any write (mismatched
        //    password/confirmPassword → 422). The college code is brand new and
        //    must NOT be persisted by the failed attempt.
        String newCode = unique("RPB");
        mockMvc.perform(post("/auth/register-college")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(unique("Partial Two"), newCode, unique("rpb_draft"), PW, "Different@1")))
            .andExpect(status().isUnprocessableEntity());

        // 3. The brand-new code survived the failure: it can now be registered
        //    with a correct request. If the failed attempt had persisted the
        //    college row, this would 422 on the duplicate code.
        String loginB = unique("rpb_admin");
        mockMvc.perform(post("/auth/register-college")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(unique("Partial Three"), newCode, loginB, PW, PW)))
            .andExpect(status().isCreated());

        // And the original account still logs in.
        loginToken(loginA, PW);
        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + loginA + "\",\"password\":\"WrongPass\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void registration_succeedsWithStaleToken_inHeaderAndAnonymousAuth_unchanged() throws Exception {
        // 1. A stale/expired/garbage token on the PUBLIC registration path must
        //    not matter — the JWT filter skips /auth/register-college entirely.
        String code = unique("RST");
        String loginId = unique("rst_admin");
        mockMvc.perform(post("/auth/register-college")
                .header("Authorization", "Bearer invalid.expired.jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(unique("Stale One"), code, loginId, PW, PW)))
            .andExpect(status().isCreated());

        // 2. The registered College Admin can log in normally.
        loginToken(loginId, PW);

        // 3. Protected endpoints are UNCHANGED: anonymous GET /auth/me is 401,
        //    while the registered admin's JWT passes.
        mockMvc.perform(get("/auth/me"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + loginToken(loginId, PW)))
            .andExpect(status().isOk());
    }
}