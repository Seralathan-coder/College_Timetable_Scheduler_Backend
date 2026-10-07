package com.erp.timetable.config;

import com.erp.timetable.module.auth.repository.CollegeRepository;
import com.erp.timetable.module.auth.repository.RoleRepository;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.availability.repository.TimeSlotRepository;
import com.erp.timetable.module.department.repository.DepartmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production MUST NOT run {@link DataInitializerConfig}.
 *
 * <p>Asserts the initializer is not even registered under the {@code prod}
 * profile and that no demo/development data lands in the database: no demo
 * users, no default tenant, no sample departments and no demo time slots. The
 * schema is created directly from the JPA entities with an isolated in-memory
 * H2 so this proves the initializer contributed nothing, without touching the
 * persistent developer database or requiring a live Postgres/Flyway.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:init_prod_disabled;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;NON_KEYWORDS=VALUE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.flyway.enabled=false"
})
@ActiveProfiles("prod")
class DataInitializerProductionDisabledTest {

    @Autowired private ApplicationContext context;
    @Autowired private Environment environment;
    @Autowired private UserRepository userRepository;
    @Autowired private CollegeRepository collegeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;

    @Test
    @DisplayName("the prod profile is the active one (the h2 default is not silently inherited)")
    void prodProfile_isActiveAndDoesNotInheritH2() {
        assertTrue(Arrays.asList(environment.getActiveProfiles()).contains("prod"),
                "expected the prod profile to be active, got "
                        + Arrays.toString(environment.getActiveProfiles()));
        assertFalse(Arrays.asList(environment.getActiveProfiles()).contains("h2"),
                "the application.yml 'spring.profiles.active: h2' default must not be active in prod");
    }

    @Test
    @DisplayName("prod does not register DataInitializerConfig or its seedData runner")
    void prod_doesNotRegisterInitializer() {
        assertTrue(context.getBeansOfType(DataInitializerConfig.class).isEmpty(),
                "DataInitializerConfig must NOT be registered under the prod profile");
        assertFalse(context.containsBean("seedData"),
                "the seedData CommandLineRunner must NOT exist under the prod profile");
    }

    @Test
    @DisplayName("prod seeds no demo users, tenant, departments, roles or time slots")
    void prod_seedsNoDevelopmentData() {
        for (String demoLogin : new String[]{"admin", "student", "faculty",
                "cse_admin", "ece_admin", "me_admin"}) {
            assertTrue(userRepository.findAllByUsername(demoLogin).isEmpty(),
                    "prod must not create the demo account '" + demoLogin + "'");
        }
        assertTrue(collegeRepository.findByCode("DEV001").isEmpty(),
                "prod must not create the DEV001 default tenant");
        assertEquals(0L, departmentRepository.count(),
                "prod must not seed sample departments");
        assertEquals(0L, timeSlotRepository.count(),
                "prod must not seed demo time slots");
        assertEquals(0L, roleRepository.count(),
                "prod must not seed roles; roles are owned by the Flyway migrations");
    }
}
