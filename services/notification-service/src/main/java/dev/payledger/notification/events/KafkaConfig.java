package dev.payledger.notification.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    /** This service owns its dead-letter topic. One partition is plenty: it should stay (nearly) empty. */
    @Bean
    NewTopic deadLetterTopic(EventProperties props) {
        return new NewTopic(props.deadLetterTopic(), 1, (short) 1);
    }

    /**
     * Retry transient failures in place (keeps per-partition order), then park the record on the
     * dead-letter topic and move on, so one bad event cannot block the partition forever.
     * Records that can never succeed (malformed JSON, unknown schema) skip the retries.
     * Boot wires this bean into the listener container factory.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template, EventProperties props) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                // Partition -1: let Kafka pick, so the DLT does not need as many partitions as the source topic.
                (record, ex) -> new TopicPartition(props.deadLetterTopic(), -1));
        var handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(props.retry().interval().toMillis(), props.retry().attempts() - 1L));
        handler.addNotRetryableExceptions(UnprocessableEventException.class, JacksonException.class);
        return handler;
    }
}
