package dev.payledger.notification.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("payledger.events")
public record EventProperties(String ledgerTopic, String deadLetterTopic, Retry retry) {

    public record Retry(int attempts, Duration interval) {
    }
}
