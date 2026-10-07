package com.erp.timetable.config;

import com.erp.timetable.module.auth.entity.RoleName;
import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.CollegeRepository;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.availability.repository.TimeSlotRepository;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local H2 initializer MUST keep running, unchanged, so local development
 * and the whole existing E2E suite (21 {@code @SpringBootTest} classes, all
 * {@code @ActiveProfiles("h2")}) keep their demo accounts.
 *
 * <p>Booted against an isolated in-memory H2 so this never touches the
 * persistent developer database.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:init_h2_activation;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE"
})
@ActiveProfiles("h2")
class DataInitializerH2ActivationTest {

    @Autowired private ApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private CollegeRepository collegeRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("H2 profile registers the seedData runner")
    void h2Profile_registersSeedDataRunner() {
        assertFalse(context.getBeansOfType(DataInitializerConfig.class).isEmpty(),
                "DataInitializerConfig must be registered under the h2 profile");
        assertTrue(context.containsBean("seedData"),
                "the seedData CommandLineRunner bean must exist under the h2 profile");
    }

    @Test
    @DisplayName("H2 profile still seeds the documented demo accounts with their passwords")
    void h2Profile_seedsDemoAccounts() {
        assertDemoAccount("admin", "Admin@1234");
        assertDemoAccount("student", "Student@1234");
        assertDemoAccount("faculty", "Faculty@1234");
    }

    @Test
    @DisplayName("H2 profile still seeds the admin's roles, tenant, department and time slots")
    void h2Profile_seedsSupportingReferenceData() {
        User admin = userRepository.findAllByUsername("admin").get(0);
        assertTrue(admin.hasRole(RoleName.ROLE_SUPER_ADMIN), "admin must keep ROLE_SUPER_ADMIN");
        assertTrue(admin.hasRole(RoleName.ROLE_COLLEGE_ADMIN),
                "admin must keep the ROLE_COLLEGE_ADMIN granted by the multi-college backfill");

        assertTrue(collegeRepository.findByCode("DEV001").isPresent(),
                "the DEV001 default tenant must still be created locally");
        assertTrue(departmentRepository.findByName("Computer Science & Engineering").isPresent(),
                "the sample CSE department must still be seeded locally");
        assertEquals(8L, timeSlotRepository.count(),
                "the 8 demo time slots must still be seeded locally");
    }

    private void assertDemoAccount(String username, String password) {
        List<User> matches = userRepository.findAllByUsername(username);
        assertFalse(matches.isEmpty(), "demo account '" + username + "' must be seeded under h2");
        User user = matches.get(0);
        assertNotNull(user.getPassword());
        assertTrue(passwordEncoder.matches(password, user.getPassword()),
                "demo account '" + username + "' must keep password " + password);
        assertTrue(Boolean.TRUE.equals(user.getIsActive()),
                "demo account '" + username + "' must be active");
    }
}
