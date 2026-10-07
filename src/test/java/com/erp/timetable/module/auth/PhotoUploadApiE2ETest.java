package com.erp.timetable.module.auth;

import com.erp.timetable.common.file.FileStorageService;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6 — Profile photo upload E2E tests.
 *
 * <p>Boots the real application on an isolated in-memory H2; uploads go through the
 * real multipart pipeline + {@link FileStorageService} into a JUnit {@link TempDir}
 * (never the repo). Real login so the real {@code JwtAuthenticationFilter} applies.
 *
 * <p>The user-required explicit case is covered by
 * {@link #missingFile_degradesToLetterAvatarFallback}: after a successful upload the
 * file is deleted from disk (as if the upload directory was cleared) and {@code GET /me}
 * must still return 200 with {@code profilePhotoUrl} omitted — the letter-initial avatar
 * fallback. No crash, no broken-image URL.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:photo_upload_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class PhotoUploadApiE2ETest {

    private static final byte[] TINY_PNG = Base64.getDecoder()
        .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @TempDir
    static Path tempUploadDir;

    @DynamicPropertySource
    static void uploadDir(DynamicPropertyRegistry registry) {
        registry.add("app.upload.dir", () -> tempUploadDir.toString());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private FileStorageService fileStorageService;

    private String loginToken(String usernameOrEmail, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + usernameOrEmail + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("data").get("accessToken").asText();
    }

    private User saveUser(String username, String email) {
        return userRepository.save(User.builder()
            .username(username)
            .email(email)
            .password(passwordEncoder.encode("Pass@1234"))
            .fullName("Photo User " + username)
            .isActive(true)
            .build());
    }

    private MockMultipartHttpServletRequestBuilder uploadRequest(String token, MockMultipartFile file) {
        MockMultipartHttpServletRequestBuilder builder = multipart("/me/photo");
        builder.file(file);
        builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private MockMultipartFile png(String name) {
        return new MockMultipartFile("file", name, "image/png", TINY_PNG);
    }

    private List<Path> listStoredFiles() {
        try (var stream = Files.list(tempUploadDir.resolve("photos"))) {
            return stream.toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    @Test
    void uploadPhoto_returnsUrl_persistsAndServesFile() throws Exception {
        User u = saveUser("phota", "phota@college.edu");
        String token = loginToken("phota", "Pass@1234");

        MvcResult result = mockMvc.perform(uploadRequest(token, png("avatar.png")))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        String url = data.get("profilePhotoUrl").asText();
        assertTrue(url.matches("/uploads/photos/[0-9a-f-]{36}\\.png"),
            "URL must be a fresh UUID-named PNG path, was: " + url);

        // Persisted in the DB (store the path, not the file).
        User persisted = userRepository.findById(u.getId()).orElseThrow();
        assertEquals(url, persisted.getProfilePhotoUrl());

        // The profile echoes the URL while the file exists.
        MvcResult me = mockMvc.perform(get("/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode meData = objectMapper.readTree(me.getResponse().getContentAsString()).get("data");
        assertEquals(url, meData.get("profilePhotoUrl").asText());

        // The file is served publicly via the /uploads resource handler (no auth needed).
        MvcResult served = mockMvc.perform(get(url))
            .andExpect(status().isOk())
            .andReturn();
        assertArrayEquals(TINY_PNG, served.getResponse().getContentAsByteArray());
    }

    @Test
    void uploadPhoto_replace_deletesOldPhotoFile() throws Exception {
        User u = saveUser("photb", "photb@college.edu");
        String token = loginToken("photb", "Pass@1234");

        MvcResult first = mockMvc.perform(uploadRequest(token, png("one.png")))
            .andExpect(status().isOk()).andReturn();
        String url1 = objectMapper.readTree(first.getResponse().getContentAsString())
            .get("data").get("profilePhotoUrl").asText();
        mockMvc.perform(get(url1)).andExpect(status().isOk());

        MvcResult second = mockMvc.perform(uploadRequest(token,
                new MockMultipartFile("file", "two.png", "image/png", TINY_PNG)))
            .andExpect(status().isOk()).andReturn();
        String url2 = objectMapper.readTree(second.getResponse().getContentAsString())
            .get("data").get("profilePhotoUrl").asText();
        assertNotEquals(url1, url2);

        // New photo is current, old file is cleaned up.
        User persisted = userRepository.findById(u.getId()).orElseThrow();
        assertEquals(url2, persisted.getProfilePhotoUrl());
        mockMvc.perform(get(url1)).andExpect(status().isNotFound());
        mockMvc.perform(get(url2)).andExpect(status().isOk());
    }

    @Test
    void missingFile_degradesToLetterAvatarFallback() throws Exception {
        User u = saveUser("photc", "photc@college.edu");
        String token = loginToken("photc", "Pass@1234");

        MvcResult upload = mockMvc.perform(uploadRequest(token, png("portrait.png")))
            .andExpect(status().isOk()).andReturn();
        String url = objectMapper.readTree(upload.getResponse().getContentAsString())
            .get("data").get("profilePhotoUrl").asText();
        mockMvc.perform(get(url)).andExpect(status().isOk());

        // BEFORE deletion: the profile carries the photo URL.
        MvcResult before = mockMvc.perform(get("/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn();
        JsonNode beforeData = objectMapper.readTree(before.getResponse().getContentAsString()).get("data");
        assertEquals(url, beforeData.get("profilePhotoUrl").asText());

        // Simulate the upload directory being cleared — the DB row remains, the file is gone.
        fileStorageService.delete(url);
        assertFalse(fileStorageService.exists(url));
        mockMvc.perform(get(url)).andExpect(status().isNotFound());

        // THE explicit fallback contract: GET /me still 200, profilePhotoUrl omitted
        // (letter-avatar degrade) — never an error, never a broken-image URL.
        MvcResult after = mockMvc.perform(get("/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode afterData = objectMapper.readTree(after.getResponse().getContentAsString()).get("data");
        JsonNode photo = afterData.get("profilePhotoUrl");
        assertTrue(photo == null || photo.isNull(),
            "missing photo file must omit profilePhotoUrl (letter-avatar fallback), was: " + photo);

        // DB still holds the stale URL (fallback is at response time, not a DB wipe).
        User persisted = userRepository.findById(u.getId()).orElseThrow();
        assertEquals(url, persisted.getProfilePhotoUrl());
    }

    @Test
    void uploadPhoto_invalidContentType_isRejected() throws Exception {
        saveUser("photd", "photd@college.edu");
        String token = loginToken("photd", "Pass@1234");

        mockMvc.perform(uploadRequest(token,
                new MockMultipartFile("file", "notes.txt", "text/plain",
                    "not an image".getBytes(StandardCharsets.UTF_8))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(result -> {
                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                assertEquals("Only JPG, PNG and WebP images are allowed", body.get("message").asText());
            });
    }

    @Test
    void uploadPhoto_renamedHtml_withImageContentType_isRejected_neverStored() throws Exception {
        // SECURITY: a spoofed payload — HTML/JS renamed to .png with a lied Content-Type
        // of image/png. It carries no image magic bytes, so it must be rejected outright.
        String payload = "<script>alert('stored xss')</script>"
            + "<html><body>not an image</body></html>";
        saveUser("photg", "photg@college.edu");
        String token = loginToken("photg", "Pass@1234");

        mockMvc.perform(uploadRequest(token,
                new MockMultipartFile("file", "evil.png", "image/png",
                    payload.getBytes(StandardCharsets.UTF_8))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(result -> {
                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                assertEquals("Only JPG, PNG and WebP images are allowed", body.get("message").asText());
            });

        // Nothing stored: the validated user has no profile photo URL at all.
        User persisted = userRepository.findByUsername("photg").orElseThrow();
        assertNull(persisted.getProfilePhotoUrl(), "spoofed upload must never produce a stored file");

        // And nothing was written under the upload directory for this user's filename.
        assertTrue(listStoredFiles().isEmpty() || listStoredFiles().stream()
                .noneMatch(p -> p.toString().contains("evil")),
            "no file may be named from client-supplied text: " + listStoredFiles());
    }

    @Test
    void uploadPhoto_extensionIsDerivedFromFileContent_notFilenameOrContentType() throws Exception {
        // SECURITY/proof: the extension must come from the validated MAGIC BYTES. A real
        // PNG payload named "notes.txt" with a fake Content-Type must still be stored as
        // /uploads/photos/<uuid>.png — never .txt, never from the client's header.
        saveUser("photh", "photh@college.edu");
        String token = loginToken("photh", "Pass@1234");

        MvcResult result = mockMvc.perform(uploadRequest(token,
                new MockMultipartFile("file", "notes.txt", "application/octet-stream", TINY_PNG)))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        String url = data.get("profilePhotoUrl").asText();
        assertTrue(url.matches("/uploads/photos/[0-9a-f-]{36}\\.png"),
            "stored extension must come from the detected ContentType (PNG magic), was: " + url);
        mockMvc.perform(get(url)).andExpect(status().isOk());
    }

    @Test
    void uploadPhoto_emptyFile_isRejected() throws Exception {
        saveUser("phote", "phote@college.edu");
        String token = loginToken("phote", "Pass@1234");

        mockMvc.perform(uploadRequest(token,
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0])))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(result -> {
                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                assertEquals("Uploaded file is empty", body.get("message").asText());
            });
    }

    @Test
    void uploadPhoto_overFiveMegabytes_isRejected() throws Exception {
        saveUser("photf", "photf@college.edu");
        String token = loginToken("photf", "Pass@1234");

        byte[] tooBig = new byte[6 * 1024 * 1024];
        // NOTE: under MockMvc the servlet-container multipart limit (MaxUploadSizeExceededException →
        // 413) is not applied; the service-level size guard is the defense exercised here (422).
        mockMvc.perform(uploadRequest(token,
                new MockMultipartFile("file", "huge.png", "image/png", tooBig)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(result -> {
                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                assertEquals("Image exceeds the 5 MB maximum size", body.get("message").asText());
            });
    }

    @Test
    void uploadPhoto_withoutToken_isRejected() throws Exception {
        mockMvc.perform(multipart("/me/photo").file(png("x.png")))
            .andExpect(status().isUnauthorized())
            .andReturn();
    }
}