package com.appsmith.server.configurations;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.StringUtils;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Fork: gives every Spring test context a PostgreSQL database (it replaces Flapdoodle's embedded MongoDB). One
 * throwaway {@code postgres:16-alpine} container (400 connections) is started per test JVM and shared by all contexts, like the
 * embedded Mongo was; Flyway creates and seeds the schema on first use. Set {@code APPSMITH_TEST_DB_URL}
 * ({@code postgresql://user:password@host:port/db}) to use an existing, disposable database instead.
 * <p>
 * Registered in {@code META-INF/spring.factories}.
 */
public class PostgresTestContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static PostgreSQLContainer<?> container;

    private static synchronized String databaseUrl() {
        final String external = System.getenv("APPSMITH_TEST_DB_URL");
        if (StringUtils.hasText(external)) {
            return external;
        }
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("appsmith")
                    .withUsername("appsmith")
                    .withPassword("appsmith-test")
                    .withCommand("postgres", "-c", "max_connections=400");
            container.start();
        }
        return "postgresql://" + container.getUsername() + ":" + container.getPassword() + "@" + container.getHost()
                + ":" + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/"
                + container.getDatabaseName();
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        // Spring keeps up to 32 test contexts alive, each with its own pool: small pools keep them all under
        // max_connections (Hikari's default of 10 each ran PostgreSQL's default of 100 out).
        TestPropertyValues.of(
                        "appsmith.db.url=" + databaseUrl(),
                        "spring.datasource.hikari.maximum-pool-size=5",
                        "spring.datasource.hikari.minimum-idle=1")
                .applyTo(context.getEnvironment());
    }
}
