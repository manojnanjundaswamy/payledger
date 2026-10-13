package dev.payledger.core.money;

import dev.payledger.core.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reference exchange rates for the currency converter: the European Central Bank's daily rates through Frankfurter
 * (free, open source, no key), as 1 INR = x OTHER. The Gulf currencies, which the ECB doesn't publish, follow their fixed
 * rate to the US dollar. Fetched at most every few hours and kept in memory; a stale copy is better than none.
 */
@Service
public class FxService {

    private static final Logger log = LoggerFactory.getLogger(FxService.class);
    /** Gulf currencies are pegged to the US dollar: units of each per 1 USD. */
    static final Map<String, Double> USD_PEGS = Map.of("AED", 3.6725, "SAR", 3.75, "QAR", 3.64, "OMR", 0.3845, "BHD", 0.376);

    public record Rates(String base, LocalDate date, Map<String, Double> rates, String source) {
    }

    private record Cached(Rates rates, Instant fetched) {
    }

    private final RestClient http;
    private final Duration refresh;
    private volatile Cached cached;

    FxService(RestClient.Builder builder, @Value("${payledger.fx.url:https://api.frankfurter.dev/v1}") String url,
              @Value("${payledger.fx.refresh:6h}") Duration refresh) {
        var settings = HttpClientSettings.defaults().withTimeouts(Duration.ofSeconds(3), Duration.ofSeconds(5));
        this.http = builder.baseUrl(url).requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings)).build();
        this.refresh = refresh;
    }

    public Rates rates() {
        Cached c = cached;
        if (c != null && c.fetched().isAfter(Instant.now().minus(refresh))) {
            return c.rates();
        }
        try {
            Rates fresh = fetch();
            cached = new Cached(fresh, Instant.now());
            return fresh;
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Exchange rates unavailable: {}", e.getMessage());
            if (c != null) {
                return c.rates();
            }
            throw new FxUnavailableException();
        }
    }

    private Rates fetch() {
        record Answer(String base, LocalDate date, Map<String, Double> rates) {
        }
        Answer a = http.get().uri("/latest?base=INR").retrieve().body(Answer.class);
        if (a == null || a.rates() == null || a.rates().isEmpty()) {
            throw new IllegalStateException("empty answer");
        }
        Map<String, Double> rates = new TreeMap<>(a.rates());
        Double usd = rates.get("USD");
        if (usd != null) {
            USD_PEGS.forEach((code, perUsd) -> rates.putIfAbsent(code, usd * perUsd));
        }
        return new Rates("INR", a.date(), new LinkedHashMap<>(rates), "European Central Bank via Frankfurter");
    }

    static class FxUnavailableException extends ApiException {
        FxUnavailableException() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "fx-unavailable", "exchange rates are unavailable right now; enter a rate yourself");
        }
    }
}
