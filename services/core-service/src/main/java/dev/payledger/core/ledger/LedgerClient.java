package dev.payledger.core.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Reads balances and period totals from ledger-service (writes go through {@link LedgerCommands}).
 * <p>
 * Reads are synchronous but optional: if the ledger is down or slow the caller gets
 * {@link Optional#empty()} and shows the rest of the page without numbers, instead of failing.
 */
@Component
@EnableConfigurationProperties(LedgerClient.Properties.class)
public class LedgerClient {

    private static final Logger log = LoggerFactory.getLogger(LedgerClient.class);
    private static final ParameterizedTypeReference<List<Balance>> BALANCES = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<PeriodTotal>> TOTALS = new ParameterizedTypeReference<>() {
    };

    /**
     * @param url     ledger-service base URL (internal network only)
     * @param apiKey  sent as X-API-Key when set (the ledger requires one in production)
     * @param timeout connect and read timeout: a page should not hang on the ledger
     */
    @ConfigurationProperties("payledger.ledger")
    public record Properties(String url, String apiKey, Duration timeout) {
    }

    public record Balance(String accountRef, String accountType, String currency, long balance) {
    }

    public record PeriodTotal(String accountRef, String accountType, String currency, LocalDate periodStart, long total) {
    }

    private final RestClient http;

    public LedgerClient(RestClient.Builder builder, Properties props) {
        var settings = HttpClientSettings.defaults().withTimeouts(props.timeout(), props.timeout());
        this.http = builder
                .baseUrl(props.url())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .defaultHeaders(h -> {
                    if (props.apiKey() != null && !props.apiKey().isBlank()) {
                        h.set("X-API-Key", props.apiKey());
                    }
                })
                .build();
    }

    public Optional<List<Balance>> balances(String bookRef) {
        return call(bookRef, () -> http.get().uri("/api/v2/books/{book}/balances", bookRef).retrieve().body(BALANCES));
    }

    /** Net movement per account of one type between two dates (inclusive), optionally per month. */
    public Optional<List<PeriodTotal>> totals(String bookRef, String accountType, LocalDate from, LocalDate to, boolean byMonth) {
        return call(bookRef, () -> http.get()
                .uri(u -> u.path("/api/v2/books/{book}/totals")
                        .queryParam("type", accountType).queryParam("from", from).queryParam("to", to)
                        .queryParam("byMonth", byMonth)
                        .build(bookRef))
                .retrieve()
                .body(TOTALS));
    }

    private static <T> Optional<List<T>> call(String bookRef, java.util.function.Supplier<List<T>> request) {
        try {
            return Optional.of(request.get());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                // The book is created by the first posting: nothing posted yet means everything is zero.
                return Optional.of(List.of());
            }
            log.warn("Ledger refused a read for {}: {}", bookRef, e.getMessage());
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Ledger unavailable for {}: {}", bookRef, e.toString());
            return Optional.empty();
        }
    }
}
