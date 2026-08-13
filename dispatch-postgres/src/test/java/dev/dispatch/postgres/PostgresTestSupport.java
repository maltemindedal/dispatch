package dev.dispatch.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared Testcontainers wiring, so every PostgreSQL test agrees on image and pool settings. */
final class PostgresTestSupport {

    static final DockerImageName IMAGE = DockerImageName.parse("postgres:17-alpine");

    private PostgresTestSupport() {
    }

    static HikariConfig config(PostgreSQLContainer<?> container, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(container.getJdbcUrl());
        config.setUsername(container.getUsername());
        config.setPassword(container.getPassword());
        config.setMaximumPoolSize(poolSize);
        // The claim query is short; a slow pool checkout means something is genuinely wrong, and
        // failing fast beats a test that hangs for thirty seconds.
        config.setConnectionTimeout(10_000);
        return config;
    }

    static HikariDataSource pool(PostgreSQLContainer<?> container, int poolSize) {
        return new HikariDataSource(config(container, poolSize));
    }
}
