package com.erp.timetable.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the production profile-default safety change: the base
 * {@code application.yml} must NOT auto-activate the {@code h2} profile.
 *
 * <p>Previously the base config carried {@code spring.profiles.active: h2}, so
 * a production run that forgot {@code SPRING_PROFILES_ACTIVE} silently booted
 * against a local H2 file database (ddl-auto=update, Flyway disabled, demo
 * data seeded). This test loads the real {@code application.yml} /
 * {@code application-h2.yml} through Spring's own config-data mechanism and
 * asserts the resolved datasource/Flyway values, so the behaviour is proven
 * without starting a context, opening a database connection, or touching any
 * data.
 *
 * <p>Assertions are made on RESOLVED PROPERTY VALUES (the definitive proof that
 * a profile file was or was not loaded) rather than on
 * {@code getActiveProfiles()} mechanics, so they are stable across how a
 * profile is activated.
 */
class ProfileDefaultConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    @DisplayName("base application.yml does NOT activate h2 by default (PostgreSQL base config wins)")
    void baseConfig_doesNotActivateH2ByDefault() {
        runner.run(ctx -> {
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.url"))
                    .as("base config datasource url")
                    .startsWith("jdbc:postgresql:");
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.driver-class-name"))
                    .as("base config driver")
                    .isEqualTo("org.postgresql.Driver");
            assertThat(ctx.getEnvironment().getProperty("spring.flyway.enabled"))
                    .as("base config Flyway is enabled")
                    .isEqualTo("true");
        });
    }

    @Test
    @DisplayName("explicitly activating h2 still loads the H2 configuration")
    void explicitH2Profile_loadsH2Configuration() {
        runner.withPropertyValues("spring.profiles.active=h2").run(ctx -> {
            assertThat(ctx.getEnvironment().getActiveProfiles())
                    .as("h2 profile is active when requested")
                    .contains("h2");
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.url"))
                    .as("H2 config datasource url")
                    .startsWith("jdbc:h2:");
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.driver-class-name"))
                    .as("H2 config driver")
                    .isEqualTo("org.h2.Driver");
            assertThat(ctx.getEnvironment().getProperty("spring.jpa.properties.hibernate.dialect"))
                    .as("H2 config dialect")
                    .isEqualTo("org.hibernate.dialect.H2Dialect");
            assertThat(ctx.getEnvironment().getProperty("spring.flyway.enabled"))
                    .as("H2 config disables Flyway")
                    .isEqualTo("false");
        });
    }
}
