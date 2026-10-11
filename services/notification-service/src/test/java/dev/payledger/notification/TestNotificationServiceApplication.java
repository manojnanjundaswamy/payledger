package dev.payledger.notification;

import org.springframework.boot.SpringApplication;

/** Runs the service against throwaway Postgres + Kafka containers: {@code ./mvnw spring-boot:test-run}. */
public class TestNotificationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(NotificationServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
