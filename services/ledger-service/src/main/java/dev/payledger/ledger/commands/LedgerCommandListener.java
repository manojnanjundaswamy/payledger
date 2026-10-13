package dev.payledger.ledger.commands;

import dev.payledger.ledger.account.AccountType;
import dev.payledger.ledger.book.BookKind;
import dev.payledger.ledger.journal.LedgerService;
import dev.payledger.ledger.journal.PostingCommand;
import dev.payledger.ledger.journal.PostingOutcome;
import dev.payledger.ledger.journal.ReversalCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Applies core-service's ledger commands (docs/events/ledger-commands.md) through the same
 * {@link LedgerService} as the HTTP API, so they get the same validation, locking and idempotency.
 * <p>
 * Delivery is at-least-once and the offset is committed only after the ledger transaction: a command
 * seen twice hits its idempotency key the second time and is a no-op.
 */
@Component
@ConditionalOnBooleanProperty(name = "payledger.commands.enabled", matchIfMissing = true)
class LedgerCommandListener {

    static final String POST = "ledger.post";
    static final String REVERSE = "ledger.reverse";
    static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(LedgerCommandListener.class);

    private final JsonMapper json;
    private final LedgerService ledger;

    LedgerCommandListener(JsonMapper json, LedgerService ledger) {
        this.json = json;
        this.ledger = ledger;
    }

    @KafkaListener(topics = "${payledger.commands.topic}", groupId = "${payledger.commands.group-id}")
    void onCommand(String payload, @Header(name = "eventType", required = false) String type,
                   @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
        Envelope envelope = json.readValue(payload, Envelope.class);
        if (envelope.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new UnprocessableCommandException("unsupported schemaVersion " + envelope.schemaVersion());
        }
        String commandType = type != null ? type : envelope.commandType();
        PostingOutcome outcome = switch (commandType) {
            case POST -> ledger.post(toPosting(json.readValue(payload, Post.class)));
            case REVERSE -> {
                Reverse r = json.readValue(payload, Reverse.class);
                yield ledger.reverse(new ReversalCommand(r.idempotencyKey(), r.reversesIdempotencyKey(), r.description()));
            }
            case null, default -> throw new UnprocessableCommandException("unknown command type " + commandType);
        };
        log.debug("Command {} ({}) for space {}: entry {}{}", envelope.commandId(), commandType, key,
                outcome.entry().id(), outcome.replayed() ? " (already applied)" : "");
    }

    private static PostingCommand toPosting(Post p) {
        try {
            return new PostingCommand(p.idempotencyKey(), p.bookRef(), BookKind.valueOf(p.bookKind()), p.entryType(),
                    p.effectiveDate(), p.description(), p.sourceRef(),
                    p.legs().stream()
                            .map(l -> new PostingCommand.Leg(l.accountRef(), AccountType.valueOf(l.accountType()), l.currency(), l.amount()))
                            .toList());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new UnprocessableCommandException("malformed ledger.post: " + e.getMessage(), e);
        }
    }

    record Envelope(UUID commandId, String commandType, int schemaVersion) {
    }

    record Post(UUID commandId, String idempotencyKey, String bookRef, String bookKind, String entryType,
                LocalDate effectiveDate, String description, String sourceRef, List<Leg> legs) {
    }

    record Leg(String accountRef, String accountType, String currency, long amount) {
    }

    record Reverse(UUID commandId, String idempotencyKey, String bookRef, String reversesIdempotencyKey, String description) {
    }
}
