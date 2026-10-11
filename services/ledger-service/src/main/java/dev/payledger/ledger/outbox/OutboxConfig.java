package dev.payledger.ledger.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxConfig {

    /**
     * The ledger owns its topic, so it declares it. KafkaAdmin creates it on startup only if it
     * does not exist yet; existing topics are never altered.
     */
    @Bean
    @ConditionalOnBooleanProperty(name = "payledger.outbox.relay.enabled", matchIfMissing = true)
    NewTopic ledgerEventsTopic(OutboxProperties props) {
        return new NewTopic(props.topic(), props.partitions(), props.replicas());
    }

    /** Backlog size and age: the first thing to alert on when Kafka or the relay is unhealthy. */
    @Bean
    MeterBinder outboxMetrics(JdbcClient jdbc) {
        return registry -> {
            Gauge.builder("payledger.outbox.pending",
                            () -> jdbc.sql("select count(*) from outbox_events where published_at is null")
                                    .query(Long.class).single())
                    .description("Outbox events not yet acknowledged by Kafka")
                    .register(registry);
            Gauge.builder("payledger.outbox.oldest.pending.age",
                            () -> jdbc.sql("""
                                            select coalesce(extract(epoch from now() - min(created_at)), 0)
                                            from outbox_events where published_at is null
                                            """)
                                    .query(Double.class).single())
                    .description("Age of the oldest unpublished outbox event")
                    .baseUnit("seconds")
                    .register(registry);
        };
    }
}
