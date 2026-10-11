package dev.payledger.ledger;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Runs the service against a real, embedded PostgreSQL 17 process: no Docker or local install needed.
 * Data persists in {@code target/dev-postgres} until {@code mvn clean}.
 * <pre>
 * ./mvnw spring-boot:test-run -Dspring-boot.run.main-class=dev.payledger.ledger.LocalDevApplication
 * </pre>
 * Dev convenience only. Production and CI use a regular Postgres (docker-compose / Testcontainers).
 */
public class LocalDevApplication {

    public static void main(String[] args) throws IOException {
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setPort(54329)
                .setDataDirectory(Path.of("target", "dev-postgres"))
                .setCleanDataDirectory(false)
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (IOException ignored) {
                // shutting down anyway
            }
        }));

        System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "");

        SpringApplication.from(LedgerServiceApplication::main).run(args);
    }
}
