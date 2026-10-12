package dev.payledger.core;

import org.springframework.boot.SpringApplication;

/** Runs the service against a throwaway Postgres container: {@code ./mvnw spring-boot:test-run}. Tokens still come from Keycloak. */
public class TestCoreServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(CoreServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
