package com.erp.timetable.module.auth;

import com.erp.timetable.module.auth.entity.Role;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.RoleRepository;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.availability.repository.TimeSlotRepository;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.department.repository.SectionRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import com.erp.timetable.module.timetable.entity.Timetable;
import com.erp.timetable.module.timetable.entity.TimetableEntry;
import com.erp.timetable.module.timetable.repository.TimetableRepository;
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

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Faculty-role experience overhaul end-to-end tests (real logins → JWT).
 *
 * <p>After the Faculty access overhaul the Faculty role sees ONLY its own
 * self-scoped data: {@code /timetable/my} (own lessons) and {@code /subjects/my}
 * (assigned subjects). Everything else — Dashboard, Master Data (departments,
 * faculty, subjects, classrooms), Availability, Reports and timetable
 * generation — is blocked server-side, not merely hidden in the menu.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:faculty_role_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class FacultyRoleE2ETest {

    private static final String PASSWORD = "Pass@1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private DepartmentRepository departmentRepository;
@Autowired private SectionRepository sectionRepository;
    @Autowired private FacultyRepository facultyRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private TimetableRepository timetableRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final AtomicLong seq = new AtomicLong();

    // ── Helpers ────────────────────────────────────────────────────────

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

    private User saveUser(String username, RoleName role, Department dept) {
        Role roleEntity = roleRepository.findByName(role)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name(role)
                .description(role.name())
                .build()));
        User user = User.builder()
            .username(username)
            .email(username + "@college.edu")
            .password(passwordEncoder.encode(PASSWORD))
            .fullName(username)
            .isActive(true)
            .department(dept)
            .build();
        user.addRole(roleEntity);
        return userRepository.save(user);
    }

    private Faculty saveFaculty(String employeeId, User owner, Department dept, Long userId) {
        return facultyRepository.save(Faculty.builder()
            .employeeId(employeeId)
            .firstName("FR")
            .lastName("Faculty")
            .email(employeeId + "@college.edu")
            .phone("9876543210")
            .department(dept)
            .designation("Professor")
            .maxDailyHours(6)
            .maxWeeklyHours(24)
            .status("AVAILABLE")
            .userId(userId)
            .build());
    }

    private Classroom saveClassroom(String roomNumber, Department dept, int capacity) {
        return classroomRepository.save(Classroom.builder()
            .roomNumber(roomNumber)
            .roomName("Room " + roomNumber)
            .building("Block-" + roomNumber)
            .department(dept)
            .roomType("LECTURE_HALL")
            .capacity(capacity)
            .status("AVAILABLE")
            .build());
    }

    private String unique() {
        return "U" + seq.incrementAndGet();
    }

    private String loginAccessToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .get("data").get("accessToken").asText();
    }

    // ── Denied: master-data lists (dashboard, departments, faculty, subjects, classrooms, reports) ──

    @Test
    void faculty_isBlockedFrom_allMasterDataAndDashboardLists() throws Exception {
        Department dept = newDepartment("FR Lists " + unique());
        User user = saveUser("fr_lists_" + unique(), RoleName.ROLE_FACULTY, dept);
        saveFaculty("FR-FAC-" + unique(), user, dept, user.getId());
        String token = loginAccessToken(user.getUsername());

        mockMvc.perform(get("/departments").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/faculty").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/subjects").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/classrooms").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/dashboard/stats").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/rooms/utilization").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
    }

    // ── Denied: single-resource reads of master data / timetables ──────────

    @Test
    void faculty_isBlockedFrom_singleResourceReads_evenOwnRecords() throws Exception {
        Department own = newDepartment("FR Reads " + unique());
        AcademicYear year = own.getAcademicYears().get(0);
        Section section = year.getSections().get(0);
        User user = saveUser("fr_reads_" + unique(), RoleName.ROLE_FACULTY, own);
        Faculty faculty = saveFaculty("FR-FAC-" + unique(), user, own, user.getId());
        Classroom classroom = saveClassroom(unique(), own, 60);
        Subject subject = subjectRepository.save(Subject.builder()
            .subjectCode("FRR" + unique().replace("U", ""))
            .subjectName("Own Subject")
            .department(own)
            .academicYear(year)
            .section(section)
            .assignedFaculty(faculty)
            .semester(1)
            .build());
        String token = loginAccessToken(user.getUsername());

        mockMvc.perform(get("/departments/" + own.getId()).header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/faculty/" + faculty.getId()).header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/subjects/" + subject.getId()).header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/classrooms/" + classroom.getId()).header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/timetable/department/" + own.getId()).header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/timetable/section/" + section.getId() + "/semester/1")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/timetable/999999").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
    }

    // ── Denied: timetable generation + management writes ────────────────

    @Test
    void faculty_isBlockedFrom_timetableGenerationAndManagementWrites() throws Exception {
        Department dept = newDepartment("FR Gen " + unique());
        AcademicYear year = dept.getAcademicYears().get(0);
        Section section = year.getSections().get(0);
        User user = saveUser("fr_gen_" + unique(), RoleName.ROLE_FACULTY, dept);
        String token = loginAccessToken(user.getUsername());

        String genBody = "{\"departmentId\":" + dept.getId() + ",\"sectionId\":" + section.getId()
            + ",\"semester\":1,\"academicSession\":\"2026-2027 ODD\"}";
        mockMvc.perform(post("/timetable/generate")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(genBody))
            .andExpect(status().isForbidden());
    }

    @Test
    void faculty_isBlockedFrom_masterDataWrites() throws Exception {
        Department own = newDepartment("FR Write " + unique());
        AcademicYear year = own.getAcademicYears().get(0);
        Section section = year.getSections().get(0);
        User user = saveUser("fr_write_" + unique(), RoleName.ROLE_FACULTY, own);
        Faculty faculty = saveFaculty("FR-FAC-" + unique(), user, own, user.getId());
        Subject subject = subjectRepository.save(Subject.builder()
            .subjectCode("FRW" + unique().replace("U", ""))
            .subjectName("Writable-Looking")
            .department(own)
            .academicYear(year)
            .section(section)
            .assignedFaculty(faculty)
            .semester(1)
            .build());
        String token = loginAccessToken(user.getUsername());

        String deptBody = "{\"name\":\"Intrude Dept\",\"building\":\"Block-Y\","
            + "\"years\":[{\"yearLabel\":\"1st Year\",\"sections\":[\"B\"]}]}";
        mockMvc.perform(post("/departments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(deptBody))
            .andExpect(status().isForbidden());

        String facBody = "{\"employeeId\":\"FR-FAC-" + unique() + "\",\"firstName\":\"A\",\"lastName\":\"B\","
            + "\"email\":\"" + unique() + "@college.edu\",\"departmentId\":" + own.getId() + "}";
        mockMvc.perform(post("/faculty")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(facBody))
            .andExpect(status().isForbidden());

        String subjBody = "{\"subjectCode\":\"FRS" + unique().replace("U", "") + "\",\"subjectName\":\"X\","
            + "\"departmentId\":" + own.getId() + ",\"academicYearId\":" + year.getId()
            + ",\"sectionId\":" + section.getId() + ",\"semester\":1}";
        mockMvc.perform(post("/subjects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(subjBody))
            .andExpect(status().isForbidden());

        mockMvc.perform(put("/departments/" + own.getId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(deptBody))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/departments/" + own.getId())
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/subjects/" + subject.getId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(subjBody))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/subjects/" + subject.getId())
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
    }

    // ── Self-scoped reads: /subjects/my ─────────────────────────────────

    @Test
    void faculty_mySubjects_returnsOnlyAssignedSubjects() throws Exception {
        Department own = newDepartment("FR Subj " + unique());
        Department other = newDepartment("FR SubjOther " + unique());
        AcademicYear ownYear = own.getAcademicYears().get(0);
        Section ownSection = ownYear.getSections().get(0);
        AcademicYear otherYear = other.getAcademicYears().get(0);
        Section otherSection = otherYear.getSections().get(0);

        User userA = saveUser("fr_subj_a_" + unique(), RoleName.ROLE_FACULTY, own);
        Faculty facA = saveFaculty("FR-FAC-" + unique(), userA, own, userA.getId());
        User userB = saveUser("fr_subj_b_" + unique(), RoleName.ROLE_FACULTY, other);
        Faculty facB = saveFaculty("FR-FAC-" + unique(), userB, other, userB.getId());

        String codeA = "MYA" + unique().replace("U", "");
        String codeB = "MYB" + unique().replace("U", "");
        subjectRepository.save(Subject.builder()
            .subjectCode(codeA).subjectName("Mine")
            .department(own).academicYear(ownYear).section(ownSection)
            .assignedFaculty(facA).semester(1).build());
        subjectRepository.save(Subject.builder()
            .subjectCode(codeB).subjectName("Someone else's")
            .department(other).academicYear(otherYear).section(otherSection)
            .assignedFaculty(facB).semester(1).build());

        String tokenA = loginAccessToken(userA.getUsername());
        MvcResult result = mockMvc.perform(get("/subjects/my").header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");

        assertEquals(1, data.size(), "faculty must see exactly their own subjects");
        assertEquals(codeA, data.get(0).get("subjectCode").asText());
        assertEquals("Mine", data.get(0).get("subjectName").asText());
    }

    // ── Self-scoped reads: /timetable/my (only lessons containing MY faculty) ──

    @Test
    void faculty_myTimetable_returnsOnlyTimetablesContainingOwnLessons() throws Exception {
        Department deptA = newDepartment("FR TtA " + unique());
        Department deptB = newDepartment("FR TtB " + unique());
        Section sectionA = deptA.getAcademicYears().get(0).getSections().get(0);
        Section sectionB = deptB.getAcademicYears().get(0).getSections().get(0);

        User userA = saveUser("fr_tt_a_" + unique(), RoleName.ROLE_FACULTY, deptA);
        Faculty facA = saveFaculty("FR-FAC-" + unique(), userA, deptA, userA.getId());
        User userB = saveUser("fr_tt_b_" + unique(), RoleName.ROLE_FACULTY, deptB);
        Faculty facB = saveFaculty("FR-FAC-" + unique(), userB, deptB, userB.getId());

        Classroom roomA = saveClassroom("R-A-" + unique(), deptA, 60);
        Classroom roomB = saveClassroom("R-B-" + unique(), deptB, 60);
        TimeSlot slot = timeSlotRepository.save(TimeSlot.builder()
            .slotOrder((int) (60 + seq.get()))
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(10, 0))
            .slotLabel("Period 1")
            .isBreak(false)
            .build());

        Subject subjA = subjectRepository.save(Subject.builder()
            .subjectCode("TTA" + unique().replace("U", "")).subjectName("A Subject")
            .department(deptA).academicYear(deptA.getAcademicYears().get(0)).section(sectionA)
            .assignedFaculty(facA).semester(1).build());
        Subject subjB = subjectRepository.save(Subject.builder()
            .subjectCode("TTB" + unique().replace("U", "")).subjectName("B Subject")
            .department(deptB).academicYear(deptB.getAcademicYears().get(0)).section(sectionB)
            .assignedFaculty(facB).semester(1).build());

        Timetable ttA = Timetable.builder()
            .academicSession("2026-2027 ODD").department(deptA).section(sectionA).semester(1).build();
        ttA.addEntry(TimetableEntry.builder()
            .dayOfWeek("MON").timeSlot(slot).subject(subjA).faculty(facA)
            .classroom(roomA).section(sectionA).build());
        Timetable savedA = timetableRepository.saveAndFlush(ttA);

        Timetable ttB = Timetable.builder()
            .academicSession("2026-2027 ODD").department(deptB).section(sectionB).semester(1).build();
        ttB.addEntry(TimetableEntry.builder()
            .dayOfWeek("MON").timeSlot(slot).subject(subjB).faculty(facB)
            .classroom(roomB).section(sectionB).build());
        timetableRepository.saveAndFlush(ttB);

        String tokenA = loginAccessToken(userA.getUsername());
        MvcResult result = mockMvc.perform(get("/timetable/my").header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");

        assertEquals(1, data.size(), "faculty must only see timetables containing their own lessons");
        assertEquals(savedA.getId().longValue(), data.get(0).get("id").asLong());
        assertTrue(data.get(0).get("entries").get(0).get("facultyName").asText().contains("FR"),
            "entry must belong to the calling faculty member");
assertEquals(subjA.getSubjectCode(), data.get(0).get("entries").get(0).get("subjectCode").asText());
    }

    // ── New: shared timetable with multiple faculty ──────────────────────

    @Test
    void faculty_myTimetable_returnsOnlyOwnEntries_withinSharedTimetable() throws Exception {
        Department dept = newDepartment("FR Shared " + unique());
        AcademicYear year = dept.getAcademicYears().get(0);
        Section section = year.getSections().get(0);

        User userA = saveUser("fr_shared_a_" + unique(), RoleName.ROLE_FACULTY, dept);
        Faculty facA = saveFaculty("FR-FAC-" + unique(), userA, dept, userA.getId());
        User userB = saveUser("fr_shared_b_" + unique(), RoleName.ROLE_FACULTY, dept);
        Faculty facB = saveFaculty("FR-FAC-" + unique(), userB, dept, userB.getId());

        Classroom classroom = saveClassroom(unique(), dept, 60);
        TimeSlot slot1 = timeSlotRepository.save(TimeSlot.builder()
            .slotOrder((int) (1000 + seq.get()))
            .startTime(LocalTime.of(8, 0))
            .endTime(LocalTime.of(9, 0))
            .slotLabel("Period 1").isBreak(false).build());
        TimeSlot slot2 = timeSlotRepository.save(TimeSlot.builder()
            .slotOrder((int) (1000 + seq.get() + 1))
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(10, 0))
            .slotLabel("Period 2").isBreak(false).build());
        TimeSlot slot3 = timeSlotRepository.save(TimeSlot.builder()
            .slotOrder((int) (1000 + seq.get() + 2))
            .startTime(LocalTime.of(10, 0))
            .endTime(LocalTime.of(11, 0))
            .slotLabel("Period 3").isBreak(false).build());

        Subject subjA = subjectRepository.save(Subject.builder()
            .subjectCode("SHA").subjectName("A Subject")
            .department(dept).academicYear(year).section(section)
            .assignedFaculty(facA).semester(1).build());
        Subject subjB = subjectRepository.save(Subject.builder()
            .subjectCode("SHB").subjectName("B Subject")
            .department(dept).academicYear(year).section(section)
            .assignedFaculty(facB).semester(1).build());

        Timetable tt = Timetable.builder()
            .academicSession("2026-2027 ODD").department(dept).section(section).semester(1).build();
        tt.addEntry(TimetableEntry.builder()
            .dayOfWeek("MON").timeSlot(slot1).subject(subjA).faculty(facA)
            .classroom(classroom).section(section).build());
        tt.addEntry(TimetableEntry.builder()
            .dayOfWeek("MON").timeSlot(slot2).subject(subjA).faculty(facA)
            .classroom(classroom).section(section).build());
        tt.addEntry(TimetableEntry.builder()
            .dayOfWeek("MON").timeSlot(slot3).subject(subjB).faculty(facB)
            .classroom(classroom).section(section).build());
        timetableRepository.saveAndFlush(tt);

        String tokenA = loginAccessToken(userA.getUsername());
        MvcResult resultA = mockMvc.perform(get("/timetable/my").header("Authorization", "Bearer " + tokenA))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode dataA = objectMapper.readTree(resultA.getResponse().getContentAsString()).get("data");

        assertEquals(1, dataA.size(), "faculty A must see exactly one timetable");
        JsonNode entriesA = dataA.get(0).get("entries");
        assertTrue(entriesA != null, "entries must not be null");
        assertEquals(2, entriesA.size(), "faculty A must see exactly 2 entries");
        for (JsonNode entry : entriesA) {
            assertTrue(entry.get("facultyName").asText().contains("FR"),
                "all returned entries must belong to faculty A");
            assertEquals("SHA", entry.get("subjectCode").asText(),
                "returned entry subject code must be A's subject");
        }

        String tokenB = loginAccessToken(userB.getUsername());
        MvcResult resultB = mockMvc.perform(get("/timetable/my").header("Authorization", "Bearer " + tokenB))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode dataB = objectMapper.readTree(resultB.getResponse().getContentAsString()).get("data");

        assertEquals(1, dataB.size(), "faculty B must see exactly one timetable");
        JsonNode entriesB = dataB.get(0).get("entries");
        assertTrue(entriesB != null, "entries must not be null");
        assertEquals(1, entriesB.size(), "faculty B must see exactly 1 entry");
        assertTrue(entriesB.get(0).get("facultyName").asText().contains("FR"),
            "returned entry must belong to faculty B");
        assertEquals("SHB", entriesB.get(0).get("subjectCode").asText(),
            "returned entry subject code must be B's subject");
    }

    // ── New: faculty with no timetable gets empty result ─────────────────

    @Test
    void faculty_myTimetable_returnsEmptyList_whenNoLessonsAssigned() throws Exception {
        Department dept = newDepartment("FR NoTt " + unique());
        User user = saveUser("fr_no_" + unique(), RoleName.ROLE_FACULTY, dept);
        saveFaculty("FR-FAC-" + unique(), user, dept, user.getId());
        String token = loginAccessToken(user.getUsername());
        MvcResult result = mockMvc.perform(get("/timetable/my").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");

        assertEquals(0, data.size(), "faculty with no assigned lessons must receive an empty array");
    }

    // ── New: the class (department / year / section) each entry belongs to ─

    /**
     * The Faculty "My Timetable" grid labels every cell with its class as
     * DEPARTMENT-YEAR-SECTION, so {@code /timetable/my} has to expose the
     * section's academic year alongside the department and section it already
     * returned. A faculty teaching the same subject in two sections must get
     * both, each with its own section - never merged, never dropped.
     */
    @Test
    void faculty_myTimetable_exposesClassDataAndKeepsEachSectionSeparate() throws Exception {
        Department dept = newDepartment("FR Class " + unique());
        AcademicYear year = dept.getAcademicYears().get(0);
        Section sectionA = year.getSections().get(0);
        Section sectionB = Section.builder().name("B").studentStrength(60).status("ACTIVE").build();
        year.addSection(sectionB);
        sectionB = sectionRepository.saveAndFlush(sectionB);

        User user = saveUser("fr_class_" + unique(), RoleName.ROLE_FACULTY, dept);
        Faculty faculty = saveFaculty("FR-FAC-" + unique(), user, dept, user.getId());

        Classroom classroom = saveClassroom(unique(), dept, 60);
        TimeSlot slot = timeSlotRepository.save(TimeSlot.builder()
            .slotOrder((int) (2000 + seq.get()))
            .startTime(LocalTime.of(9, 0))
            .endTime(LocalTime.of(10, 0))
            .slotLabel("Period 1").isBreak(false).build());

        Subject subjA = subjectRepository.save(Subject.builder()
            .subjectCode("CLA").subjectName("Section A Subject")
            .department(dept).academicYear(year).section(sectionA)
            .assignedFaculty(faculty).semester(1).build());
        Subject subjB = subjectRepository.save(Subject.builder()
            .subjectCode("CLB").subjectName("Section B Subject")
            .department(dept).academicYear(year).section(sectionB)
            .assignedFaculty(faculty).semester(1).build());

        Timetable ttA = Timetable.builder()
            .academicSession("2026-2027 ODD").department(dept).section(sectionA).semester(1).build();
        ttA.addEntry(TimetableEntry.builder()
            .dayOfWeek("MON").timeSlot(slot).subject(subjA).faculty(faculty)
            .classroom(classroom).section(sectionA).build());
        timetableRepository.saveAndFlush(ttA);

        Timetable ttB = Timetable.builder()
            .academicSession("2026-2027 ODD").department(dept).section(sectionB).semester(1).build();
        ttB.addEntry(TimetableEntry.builder()
            .dayOfWeek("MON").timeSlot(slot).subject(subjB).faculty(faculty)
            .classroom(classroom).section(sectionB).build());
        timetableRepository.saveAndFlush(ttB);

        String token = loginAccessToken(user.getUsername());
        MvcResult result = mockMvc.perform(get("/timetable/my").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");

        assertEquals(2, data.size(),
            "a faculty teaching two sections must receive one timetable per section");

        for (JsonNode timetable : data) {
            assertEquals(dept.getName(), timetable.get("departmentName").asText(),
                "department name is needed to build DEPARTMENT-YEAR-SECTION");
            assertEquals("1st Year", timetable.get("yearLabel").asText(),
                "the section's academic year label must be exposed for the class label");
            assertFalse(timetable.get("academicYearId").isNull(),
                "academicYearId must be exposed alongside yearLabel");
            assertEquals(1, timetable.get("entries").size(),
                "each section's timetable carries only this faculty's own entry");
            assertEquals(faculty.getId().longValue(),
                timetable.get("entries").get(0).get("facultyId").asLong(),
                "faculty isolation must be preserved: only the caller's own lessons");
        }

        // The two sections stay distinguishable, which is what lets the grid
        // label one cell DEPT-I-A and the other DEPT-I-B.
        List<String> sections = new ArrayList<>();
        List<String> subjects = new ArrayList<>();
        for (JsonNode timetable : data) {
            sections.add(timetable.get("sectionName").asText());
            subjects.add(timetable.get("entries").get(0).get("subjectCode").asText());
        }
        assertTrue(sections.contains("A") && sections.contains("B"),
            "both sections must be returned separately, got: " + sections);
        assertTrue(subjects.contains("CLA") && subjects.contains("CLB"),
            "both sections' subjects must be returned, got: " + subjects);
    }

    // ── Profile stays available for Faculty ──────────────────────────────

    @Test
    void faculty_profile_remainsAccessible() throws Exception {
        Department dept = newDepartment("FR Profile " + unique());
        User user = saveUser("fr_profile_" + unique(), RoleName.ROLE_FACULTY, dept);
        saveFaculty("FR-FAC-" + unique(), user, dept, user.getId());
        String token = loginAccessToken(user.getUsername());

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }
}