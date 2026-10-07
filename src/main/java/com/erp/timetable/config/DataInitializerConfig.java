package com.erp.timetable.config;

import com.erp.timetable.module.auth.entity.College;
import com.erp.timetable.module.auth.entity.Institution;
import com.erp.timetable.module.auth.entity.Role;
import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.CollegeRepository;
import com.erp.timetable.module.auth.repository.InstitutionRepository;
import com.erp.timetable.module.auth.repository.RoleRepository;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.availability.entity.TimeSlot;
import com.erp.timetable.module.availability.repository.TimeSlotRepository;
import com.erp.timetable.module.classroom.entity.Classroom;
import com.erp.timetable.module.classroom.repository.ClassroomRepository;
import com.erp.timetable.module.department.entity.AcademicYear;
import com.erp.timetable.module.department.entity.Department;
import com.erp.timetable.module.department.entity.Section;
import com.erp.timetable.module.department.repository.AcademicYearRepository;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import com.erp.timetable.module.department.repository.SectionRepository;
import com.erp.timetable.module.faculty.entity.Faculty;
import com.erp.timetable.module.faculty.repository.FacultyRepository;
import com.erp.timetable.module.subject.entity.Subject;
import com.erp.timetable.module.subject.repository.SubjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Seeds demo/development data (roles, demo accounts, time slots, sample
 * departments, faculty, classrooms, subjects) and performs the legacy
 * single-tenant -&gt; multi-college backfill.
 *
 * <p><strong>DEVELOPMENT ONLY.</strong> This bean is restricted to the local
 * {@code h2} and {@code dev} profiles by an explicit allow-list, so it is
 * disabled for {@code prod} and for every other profile. The allow-list is
 * deliberate: it fails CLOSED, so an unrecognised or newly introduced
 * production profile (staging, qa, ...) never gets demo data, whereas a
 * deny-list of production profile names would fail OPEN.
 *
 * <p>Why production must not run this: besides creating demo rows, the runner
 * also <em>modifies existing</em> records - it rewrites the password of any
 * existing {@code student}/{@code faculty} account to the documented demo
 * credentials, forces {@code isActive=true}, backfills {@code college_id} on
 * every user/department/faculty, and grants {@code ROLE_COLLEGE_ADMIN} to every
 * {@code ROLE_SUPER_ADMIN}. In production the schema and the tenant backfill
 * are owned by Flyway (V9 creates the institution row, V12 creates the DEV001
 * tenant, backfills {@code college_id} and grants {@code ROLE_COLLEGE_ADMIN}
 * to platform admins), so this runner is redundant there and actively unsafe.
 */
@Profile({"h2", "dev"})
@Configuration
@RequiredArgsConstructor
@Slf4j
public class DataInitializerConfig {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final InstitutionRepository institutionRepository;
    private final PasswordEncoder passwordEncoder;
    private final TimeSlotRepository timeSlotRepository;
    private final DepartmentRepository departmentRepository;
    private final FacultyRepository facultyRepository;
    private final ClassroomRepository classroomRepository;
    private final SubjectRepository subjectRepository;
    private final AcademicYearRepository academicYearRepository;
    private final SectionRepository sectionRepository;
    private final CollegeRepository collegeRepository;

    @Bean
    public CommandLineRunner seedData() {
        return args -> {
            // ── Seed Roles ──────────────────────────────────────
            List<RoleName> roleNames = Arrays.asList(
                RoleName.ROLE_SUPER_ADMIN,
                RoleName.ROLE_COLLEGE_ADMIN,
                RoleName.ROLE_HOD,
                RoleName.ROLE_FACULTY,
                RoleName.ROLE_EXAM_COORDINATOR,
                RoleName.ROLE_STUDENT
            );

            roleNames.forEach(name -> {
                if (roleRepository.findByName(name).isEmpty()) {
                    roleRepository.save(Role.builder()
                        .name(name)
                        .description(name.name().replace("ROLE_", "").replace("_", " "))
                        .build());
                }
            });

            // ── Seed Admin User (Super Administrator) ──────────────────
            if (findSeedUser("admin").isEmpty()) {
                Role superAdmin = roleRepository.findByName(RoleName.ROLE_SUPER_ADMIN).orElseThrow();
                User admin = User.builder()
                    .username("admin")
                    .email("admin@college.edu")
                    .fullName("Super Administrator")
                    .password(passwordEncoder.encode("Admin@1234"))
                    .isActive(true)
                    .build();
                admin.addRole(superAdmin);
                userRepository.save(admin);
                log.info("✅ Super Admin user created (admin / Admin@1234)");
            }

            // ── Seed default Institution (single global row, id = 1) ────
            if (institutionRepository.findById(Institution.SINGLETON_ID).isEmpty()) {
                institutionRepository.save(Institution.builder()
                    .id(Institution.SINGLETON_ID)
                    .name("Default Institution")
                    .build());
                log.info("✅ Default Institution row created");
            }

            // ── Seed default College + additive multi-college backfill ─────
            // wraps every pre-existing user/department/faculty into a single
            // default tenant (code DEV001) so the data model is immediately
            // multi-college-safe on BOTH fresh and legacy databases. Idempotent.
            migrateLegacyDataIntoDefaultCollege();

            // ── Seed Time Slots ──────────────────────────────────
            if (timeSlotRepository.count() == 0) {
                List<TimeSlot> slots = List.of(
                    TimeSlot.builder().slotOrder(1).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(9, 50)).isBreak(false).slotLabel("Period 1").build(),
                    TimeSlot.builder().slotOrder(2).startTime(LocalTime.of(9, 50)).endTime(LocalTime.of(10, 40)).isBreak(false).slotLabel("Period 2").build(),
                    TimeSlot.builder().slotOrder(3).startTime(LocalTime.of(10, 50)).endTime(LocalTime.of(11, 40)).isBreak(false).slotLabel("Period 3").build(),
                    TimeSlot.builder().slotOrder(4).startTime(LocalTime.of(11, 40)).endTime(LocalTime.of(12, 30)).isBreak(false).slotLabel("Period 4").build(),
                    TimeSlot.builder().slotOrder(5).startTime(LocalTime.of(12, 30)).endTime(LocalTime.of(13, 30)).isBreak(true).slotLabel("Lunch Break").build(),
                    TimeSlot.builder().slotOrder(6).startTime(LocalTime.of(13, 30)).endTime(LocalTime.of(14, 20)).isBreak(false).slotLabel("Period 5").build(),
                    TimeSlot.builder().slotOrder(7).startTime(LocalTime.of(14, 20)).endTime(LocalTime.of(15, 10)).isBreak(false).slotLabel("Period 6").build(),
                    TimeSlot.builder().slotOrder(8).startTime(LocalTime.of(15, 15)).endTime(LocalTime.of(16, 5)).isBreak(false).slotLabel("Period 7").build()
                );
                timeSlotRepository.saveAll(slots);
                log.info("✅ Seeded 8 Schedule Time Slots");
            }

            // ── Seed Sample Departments & Department-Specific Logins ───
            if (departmentRepository.count() == 0) {
                Department cse = createDepartmentWithYears("Computer Science & Engineering", "Dr. A. Sharma", "cse@college.edu", "+91 9876543210", "Block A");
                Department ece = createDepartmentWithYears("Electronics & Communication", "Dr. B. Verma", "ece@college.edu", "+91 9876543211", "Block B");
                Department me = createDepartmentWithYears("Mechanical Engineering", "Dr. C. Patel", "me@college.edu", "+91 9876543212", "Block C");
                departmentRepository.saveAll(List.of(cse, ece, me));
                log.info("✅ Seeded 3 Departments with Academic Years & Sections");

                // Seed Department Logins (HOD Accounts)
                Role hodRole = roleRepository.findByName(RoleName.ROLE_HOD).orElseThrow();

                User cseUser = User.builder().username("cse_admin").email("cse_admin@college.edu").fullName("CSE HOD Administrator").password(passwordEncoder.encode("Admin@1234")).department(cse).isActive(true).build();
                cseUser.addRole(hodRole);

                User eceUser = User.builder().username("ece_admin").email("ece_admin@college.edu").fullName("ECE HOD Administrator").password(passwordEncoder.encode("Admin@1234")).department(ece).isActive(true).build();
                eceUser.addRole(hodRole);

                User meUser = User.builder().username("me_admin").email("me_admin@college.edu").fullName("ME HOD Administrator").password(passwordEncoder.encode("Admin@1234")).department(me).isActive(true).build();
                meUser.addRole(hodRole);

                userRepository.saveAll(List.of(cseUser, eceUser, meUser));
                log.info("✅ Seeded 3 Department Logins (cse_admin, ece_admin, me_admin / Admin@1234)");

                // Seed Faculty (Primary + Shared)
                Faculty f1 = Faculty.builder().employeeId("FAC001").firstName("Rajesh").lastName("Kumar").email("rajesh@college.edu").phone("9876543210").department(cse).teachingDepartments(String.valueOf(ece.getId())).designation("Professor").specialization("Algorithms").maxDailyHours(4).maxWeeklyHours(24).status("AVAILABLE").build();
                Faculty f2 = Faculty.builder().employeeId("FAC002").firstName("Priya").lastName("Nair").email("priya@college.edu").phone("9876543211").department(cse).teachingDepartments(String.valueOf(me.getId())).designation("Associate Professor").specialization("Database Systems").maxDailyHours(4).maxWeeklyHours(20).status("AVAILABLE").build();
                Faculty f3 = Faculty.builder().employeeId("FAC003").firstName("Anil").lastName("Deshmukh").email("anil@college.edu").phone("9876543212").department(ece).teachingDepartments(String.valueOf(cse.getId())).designation("Assistant Professor").specialization("VLSI Design").maxDailyHours(4).maxWeeklyHours(18).status("AVAILABLE").build();
                facultyRepository.saveAll(List.of(f1, f2, f3));
                log.info("✅ Seeded 3 Faculty members with Shared Department permissions");

                // Seed Classrooms
                Classroom r1 = Classroom.builder().roomNumber("CS-101").roomName("CSE Lecture Hall 1").building("Block A").department(cse).roomType("LECTURE_HALL").capacity(70).floor(1).status("AVAILABLE").build();
                Classroom r2 = Classroom.builder().roomNumber("CS-LAB1").roomName("Advanced Programming Lab").building("Block A").department(cse).roomType("LAB").capacity(40).floor(1).status("AVAILABLE").build();
                Classroom r3 = Classroom.builder().roomNumber("EC-201").roomName("ECE Seminar Hall").building("Block B").department(ece).roomType("SEMINAR_ROOM").capacity(120).floor(2).status("AVAILABLE").build();
                classroomRepository.saveAll(List.of(r1, r2, r3));
                log.info("✅ Seeded 3 Classrooms");

                // Seed Subjects
                AcademicYear year1 = cse.getAcademicYears().get(0);
                Section secA = year1.getSections().get(0);
                Subject s1 = Subject.builder().subjectCode("CS201").subjectName("Data Structures & Algorithms").department(cse).academicYear(year1).section(secA).assignedFaculty(f1).semester(3).credits(4).theoryHours(3).practicalHours(0).subjectType("THEORY").isActive(true).build();
                Subject s2 = Subject.builder().subjectCode("CS202").subjectName("Database Management Systems").department(cse).academicYear(year1).section(secA).assignedFaculty(f2).semester(3).credits(4).theoryHours(3).practicalHours(0).subjectType("THEORY").isActive(true).build();
                Subject s3 = Subject.builder().subjectCode("CS205L").subjectName("DBMS Lab").department(cse).academicYear(year1).section(secA).assignedFaculty(f2).semester(3).credits(2).theoryHours(0).practicalHours(3).subjectType("LAB").isActive(true).build();
                subjectRepository.saveAll(List.of(s1, s2, s3));
                log.info("✅ Seeded 3 Subjects");
            } else {
                restoreMissingCseSeed();
            }

            // ── Seed Student Account (ROLE_STUDENT) ─────────────────────
            // Own block (not inside the department-count check) so it also runs
            // on databases that were seeded before ROLE_STUDENT existed.
            // Repositories are used instead of traversing the LAZY
            // department.academicYears/sections collections (no transaction
            // here, open-in-view is off).
            Department cse = departmentRepository.findByName("Computer Science & Engineering").orElse(null);
            if (cse != null) {
                academicYearRepository.findByDepartmentIdAndYearLabel(cse.getId(), "1st Year")
                    .ifPresent(year1 -> {
                        Role studentRole = roleRepository.findByName(RoleName.ROLE_STUDENT).orElseThrow();
                        Long sectionId = sectionRepository.findByAcademicYearId(year1.getId()).stream()
                            .findFirst().map(Section::getId).orElse(null);

                        User studentUser = findSeedUser("student").orElse(null);
                        if (studentUser == null) {
                            studentUser = User.builder()
                                .username("student")
                                .email("student@college.edu")
                                .fullName("Demo Student")
                                .password(passwordEncoder.encode("Student@1234"))
                                .department(cse)
                                .academicYearId(year1.getId())
                                .sectionId(sectionId)
                                .isActive(true)
                                .build();
                            studentUser.addRole(studentRole);
                            userRepository.save(studentUser);
                            log.info("✅ Student user created (student / Student@1234)");
                        } else {
                            // Deterministic dev/demo account: repair password, role
                            // and class identity so the documented DEV credentials
                            // always work, regardless of prior DB state (legacy
                            // record, changed hash, missing role, inactive, etc.).
                            boolean dirty = false;
                            if (!passwordEncoder.matches("Student@1234", studentUser.getPassword())) {
                                dirty = true;
                            }
                            if (!studentUser.hasRole(RoleName.ROLE_STUDENT)) {
                                studentUser.addRole(studentRole);
                                dirty = true;
                            }
                            if (studentUser.getDepartment() == null) {
                                studentUser.setDepartment(cse);
                                dirty = true;
                            }
                            if (studentUser.getAcademicYearId() == null) {
                                studentUser.setAcademicYearId(year1.getId());
                                dirty = true;
                            }
                            if (studentUser.getSectionId() == null) {
                                studentUser.setSectionId(sectionId);
                                dirty = true;
                            }
                            if (!Boolean.TRUE.equals(studentUser.getIsActive())) {
                                studentUser.setIsActive(true);
                                dirty = true;
                            }
                            if (dirty) {
                                studentUser.setPassword(passwordEncoder.encode("Student@1234"));
                                userRepository.save(studentUser);
                                log.info("✅ Existing 'student' user repaired to documented dev credentials (student / Student@1234)");
                            }
                        }
                    });
            }

            // ── Seed Faculty Login (ROLE_FACULTY → FAC001) ──────────────
            // Deterministic dev/test account linked to the seeded faculty record
            // (FAC001 / Rajesh Kumar / CSE) via Faculty.userId, so the account's
            // self-scoped timetable + availability resolve out of the box.
            if (cse != null) {
                facultyRepository.findByEmployeeId("FAC001").ifPresent(rajesh -> {
                    Role facultyRole = roleRepository.findByName(RoleName.ROLE_FACULTY).orElseThrow();

                    User facultyUser = findSeedUser("faculty").orElse(null);
                    boolean dirty = false;
                    if (facultyUser == null) {
                        facultyUser = User.builder()
                            .username("faculty")
                            .email("faculty@college.edu")
                            .fullName("Demo Faculty")
                            .password(passwordEncoder.encode("Faculty@1234"))
                            .department(cse)
                            .isActive(true)
                            .build();
                        facultyUser.addRole(facultyRole);
                        userRepository.save(facultyUser);
                        log.info("✅ Faculty user created (faculty / Faculty@1234)");
                    } else {
                        if (!passwordEncoder.matches("Faculty@1234", facultyUser.getPassword())) {
                            facultyUser.setPassword(passwordEncoder.encode("Faculty@1234"));
                            dirty = true;
                        }
                        if (!facultyUser.hasRole(RoleName.ROLE_FACULTY)) {
                            facultyUser.addRole(facultyRole);
                            dirty = true;
                        }
                        if (facultyUser.getDepartment() == null) {
                            facultyUser.setDepartment(cse);
                            dirty = true;
                        }
                        if (!Boolean.TRUE.equals(facultyUser.getIsActive())) {
                            facultyUser.setIsActive(true);
                            dirty = true;
                        }
                        if (dirty) {
                            userRepository.save(facultyUser);
                            log.info("✅ Existing 'faculty' user repaired to documented dev credentials (faculty / Faculty@1234)");
                        }
                    }

                    // Link the demo account to the seeded faculty record — but do
                    // not clobber a link already claimed by another account.
                    if (rajesh.getUserId() == null || rajesh.getUserId().equals(facultyUser.getId())) {
                        rajesh.setUserId(facultyUser.getId());
                        facultyRepository.save(rajesh);
                    }
                });
            }
        };
    }

    private Department createDepartmentWithYears(String name, String hod, String email, String phone, String building) {
        Department dept = Department.builder()
            .name(name)
            .hodName(hod)
            .contactEmail(email)
            .contactPhone(phone)
            .building(building)
            .isArchived(false)
            .build();

        for (String yearLabel : List.of("1st Year", "2nd Year", "3rd Year", "4th Year")) {
            AcademicYear year = AcademicYear.builder().yearLabel(yearLabel).isEnabled(true).build();
            year.addSection(Section.builder().name("A").studentStrength(60).status("ACTIVE").build());
            year.addSection(Section.builder().name("B").studentStrength(60).status("ACTIVE").build());
            dept.addAcademicYear(year);
        }
        return dept;
    }

    /**
     * Repair path for legacy/polluted databases that already contain data but
     * are missing the canonical {@code Computer Science & Engineering} seed
     * department that the engine integration tests and demo documentation rely
     * on (the all-or-nothing department seed only runs on an empty database).
     * Idempotent and strictly additive: every object is created only when its
     * natural key (department name / username / employee id / room number /
     * subject code) is absent; existing rows are never modified or duplicated.
     */
    private void restoreMissingCseSeed() {
        Department existing = departmentRepository.findByName("Computer Science & Engineering").orElse(null);
        if (existing != null) {
            return;
        }

        College college = collegeRepository.findByCode("DEV001").orElse(null);

        Department cse = createDepartmentWithYears("Computer Science & Engineering", "Dr. A. Sharma", "cse@college.edu", "+91 9876543210", "Block A");
        if (college != null) {
            cse.setCollege(college);
        }
        cse = departmentRepository.save(cse);
        log.info("✅ Restored missing seed department: Computer Science & Engineering (id {})", cse.getId());

        if (findSeedUser("cse_admin").isEmpty()) {
            Role hodRole = roleRepository.findByName(RoleName.ROLE_HOD).orElseThrow();
            User cseUser = User.builder()
                .username("cse_admin")
                .email("cse_admin@college.edu")
                .fullName("CSE HOD Administrator")
                .password(passwordEncoder.encode("Admin@1234"))
                .department(cse)
                .isActive(true)
                .build();
            cseUser.addRole(hodRole);
            if (college != null) {
                cseUser.setCollege(college);
            }
            userRepository.save(cseUser);
            log.info("✅ Restored missing seed HOD login: cse_admin");
        }

        AcademicYear year1 = cse.getAcademicYears().get(0);
        Section secA = year1.getSections().get(0);

        Faculty f1 = facultyRepository.findByEmployeeId("FAC001").orElse(null);
        if (f1 == null) {
            f1 = Faculty.builder().employeeId("FAC001").firstName("Rajesh").lastName("Kumar").email("rajesh@college.edu").phone("9876543210").department(cse).teachingDepartments(String.valueOf(cse.getId())).designation("Professor").specialization("Algorithms").maxDailyHours(4).maxWeeklyHours(24).status("AVAILABLE").build();
            if (college != null) {
                f1.setCollege(college);
            }
            f1 = facultyRepository.save(f1);
            log.info("✅ Restored missing seed faculty: FAC001");
        }

        Faculty f2 = facultyRepository.findByEmployeeId("FAC002").orElse(null);
        if (f2 == null) {
            f2 = Faculty.builder().employeeId("FAC002").firstName("Priya").lastName("Nair").email("priya@college.edu").phone("9876543211").department(cse).teachingDepartments(String.valueOf(cse.getId())).designation("Associate Professor").specialization("Database Systems").maxDailyHours(4).maxWeeklyHours(20).status("AVAILABLE").build();
            if (college != null) {
                f2.setCollege(college);
            }
            f2 = facultyRepository.save(f2);
            log.info("✅ Restored missing seed faculty: FAC002");
        }

        if (classroomRepository.findByRoomNumber("CS-101").isEmpty()) {
            Classroom r1 = Classroom.builder().roomNumber("CS-101").roomName("CSE Lecture Hall 1").building("Block A").department(cse).roomType("LECTURE_HALL").capacity(70).floor(1).status("AVAILABLE").build();
            classroomRepository.save(r1);
            log.info("✅ Restored missing seed classroom: CS-101");
        }

        if (classroomRepository.findByRoomNumber("CS-LAB1").isEmpty()) {
            Classroom r2 = Classroom.builder().roomNumber("CS-LAB1").roomName("Advanced Programming Lab").building("Block A").department(cse).roomType("LAB").capacity(40).floor(1).status("AVAILABLE").build();
            classroomRepository.save(r2);
            log.info("✅ Restored missing seed classroom: CS-LAB1");
        }

        if (!subjectRepository.existsBySubjectCode("CS201")) {
            Subject s1 = Subject.builder().subjectCode("CS201").subjectName("Data Structures & Algorithms").department(cse).academicYear(year1).section(secA).assignedFaculty(f1).semester(3).credits(4).theoryHours(3).practicalHours(0).subjectType("THEORY").isActive(true).build();
            subjectRepository.save(s1);
            log.info("✅ Restored missing seed subject: CS201");
        }
        if (!subjectRepository.existsBySubjectCode("CS202")) {
            Subject s2 = Subject.builder().subjectCode("CS202").subjectName("Database Management Systems").department(cse).academicYear(year1).section(secA).assignedFaculty(f2).semester(3).credits(4).theoryHours(3).practicalHours(0).subjectType("THEORY").isActive(true).build();
            subjectRepository.save(s2);
            log.info("✅ Restored missing seed subject: CS202");
        }
        if (!subjectRepository.existsBySubjectCode("CS205L")) {
            Subject s3 = Subject.builder().subjectCode("CS205L").subjectName("DBMS Lab").department(cse).academicYear(year1).section(secA).assignedFaculty(f2).semester(3).credits(2).theoryHours(0).practicalHours(3).subjectType("LAB").isActive(true).build();
            subjectRepository.save(s3);
            log.info("✅ Restored missing seed subject: CS205L");
        }
    }

    /**
     * Resolves a seed account by username. Login IDs are only unique per
     * college, so the same username (e.g. "admin" or "student") may legitimately
     * exist on several tenants. The deterministic seed/dev accounts are anchored
     * to the default DEV001 college (or a legacy row with no college), so that
     * account is preferred; otherwise the first match wins.
     */
    private Optional<User> findSeedUser(String username) {
        List<User> matches = userRepository.findAllByUsername(username);
        return matches.stream()
            .filter(u -> u.getCollege() == null || "DEV001".equals(u.getCollege().getCode()))
            .findFirst()
            .or(() -> matches.stream().findFirst());
    }

    /**
     * Bootstraps the multi-college data model on ANY database (fresh or
     * legacy): creates the single default tenant College (code DEV001),
     * backfills every existing department / faculty / user that has no college,
     * and grants the platform {@code ROLE_COLLEGE_ADMIN} to the dev admin.
     * Pure additive — never deletes, resets, duplicates or renames any row.
     */
    private void migrateLegacyDataIntoDefaultCollege() {
        College defaultCollege = collegeRepository.findByCode("DEV001").orElse(null);
        if (defaultCollege == null) {
            Institution institution = institutionRepository.findById(Institution.SINGLETON_ID).orElse(null);
            defaultCollege = College.builder()
                .code("DEV001")
                .name(institution != null ? institution.getName() : "Default College")
                .address(institution != null ? institution.getAddress() : null)
                .isActive(true)
                .build();
            defaultCollege = collegeRepository.save(defaultCollege);
            log.info("✅ Default College created (code DEV001, id {})", defaultCollege.getId());
        }

        int deptBackfilled = 0;
        for (Department d : departmentRepository.findAll()) {
            if (d.getCollege() == null) {
                d.setCollege(defaultCollege);
                departmentRepository.save(d);
                deptBackfilled++;
            }
        }
        if (deptBackfilled > 0) {
            log.info("✅ Backfilled {} departments into default college", deptBackfilled);
        }

        int facultyBackfilled = 0;
        for (Faculty f : facultyRepository.findAll()) {
            if (f.getCollege() == null) {
                f.setCollege(f.getDepartment() != null && f.getDepartment().getCollege() != null
                    ? f.getDepartment().getCollege() : defaultCollege);
                facultyRepository.save(f);
                facultyBackfilled++;
            }
        }
        if (facultyBackfilled > 0) {
            log.info("✅ Backfilled {} faculty members into default college", facultyBackfilled);
        }

        int userBackfilled = 0;
        Role collegeAdminRole = roleRepository.findByName(RoleName.ROLE_COLLEGE_ADMIN).orElseThrow();
        for (User u : userRepository.findAll()) {
            boolean dirty = false;
            if (u.getCollege() == null) {
                u.setCollege(defaultCollege);
                dirty = true;
            }
            if (u.hasRole(RoleName.ROLE_SUPER_ADMIN) && !u.hasRole(RoleName.ROLE_COLLEGE_ADMIN)) {
                u.addRole(collegeAdminRole);
                dirty = true;
            }
            if (dirty) {
                userRepository.save(u);
                if (!u.hasRole(RoleName.ROLE_COLLEGE_ADMIN) || u.getCollege() != null) {
                    userBackfilled++;
                }
            }
        }
        if (userBackfilled > 0) {
            log.info("✅ Backfilled {} users into default college / granted ROLE_COLLEGE_ADMIN to platform admins", userBackfilled);
        }
    }
}
