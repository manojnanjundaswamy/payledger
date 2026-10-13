package dev.payledger.ledger.commands;

import dev.payledger.ledger.common.LedgerException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CommandConsumerConfig.CommandProperties.class)
@ConditionalOnBooleanProperty(name = "payledger.commands.enabled", matchIfMissing = true)
class CommandConsumerConfig {

    /**
     * @param topic           core-service's command topic
     * @param groupId         consumer group: every ledger instance shares it, so each command is applied by one of them
     * @param deadLetterTopic where commands that can never be applied are parked (should stay empty: alert on it)
     * @param maxBackoff      longest wait between retries of a transient failure (database down, ...)
     */
    @ConfigurationProperties("payledger.commands")
    record CommandProperties(String topic, String groupId, String deadLetterTopic, Duration maxBackoff) {
    }

    @Bean
    NewTopic commandDeadLetterTopic(CommandProperties props) {
        return new NewTopic(props.deadLetterTopic(), 1, (short) 1);
    }

    /**
     * Unlike notifications, a dead-lettered command is a user's money that never reached the ledger. So:
     * <ul>
     *   <li>transient failures are retried <em>forever</em> with exponential backoff, in place, which keeps the
     *       partition's order (a reversal must never overtake its entry). Commands queue up meanwhile; nothing is lost;</li>
     *   <li>only commands that can never succeed (bad JSON, unknown type, a ledger rule violated) go to the
     *       dead-letter topic at once. core-service validates first, so that would be a bug.</li>
     * </ul>
     * Boot wires this bean into the listener container factory.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template, CommandProperties props) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(props.deadLetterTopic(), -1));
        var backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxInterval(props.maxBackoff().toMillis());
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(UnprocessableCommandException.class, JacksonException.class, LedgerException.class);
        return handler;
    }
}
