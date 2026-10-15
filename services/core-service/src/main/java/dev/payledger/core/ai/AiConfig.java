package dev.payledger.core.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Locale;

/**
 * Which model reads for us, and what it costs. Nothing here is tied to one provider: set AI_PROVIDER, AI_BASE_URL,
 * AI_API_KEY and AI_MODEL (and the prices, for the cost log). With no key, AI is off and everything falls back to the
 * rules and to asking the person, exactly as before.
 */
@Configuration
class AiConfig {

    /** Prices per million tokens in US dollars, and per minute of audio; only used to log what each call cost. */
    record Prices(double inputPerMillion, double outputPerMillion, double audioPerMinute) {

        long costMicros(int inputTokens, int outputTokens) {
            return Math.round(inputTokens * inputPerMillion + outputTokens * outputPerMillion);
        }

        long audioMicros(double seconds) {
            return Math.round(seconds / 60.0 * audioPerMinute * 1_000_000);
        }
    }

    @Bean
    Prices aiPrices(@Value("${payledger.ai.price.input-per-million:1.0}") double in,
                    @Value("${payledger.ai.price.output-per-million:5.0}") double out,
                    @Value("${payledger.ai.price.audio-per-minute:0.006}") double audio) {
        return new Prices(in, out, audio);
    }

    @Bean
    LlmClient llmClient(RestClient.Builder builder, JsonMapper json,
                        @Value("${payledger.ai.provider:anthropic}") String provider,
                        @Value("${payledger.ai.base-url:}") String baseUrl,
                        @Value("${payledger.ai.api-key:}") String apiKey,
                        @Value("${payledger.ai.model:claude-haiku-5-5}") String model,
                        @Value("${payledger.ai.timeout:20s}") Duration timeout) {
        if (apiKey.isBlank() || provider.isBlank() || "none".equalsIgnoreCase(provider)) {
            return new NoLlm();
        }
        RestClient.Builder http = builder.clone().requestFactory(ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults().withTimeouts(Duration.ofSeconds(5), timeout)));
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "anthropic" -> new AnthropicClient(http, json, baseUrl.isBlank() ? "https://api.anthropic.com" : baseUrl, apiKey, model);
            case "openai", "openai-compatible", "openrouter" -> new OpenAiCompatibleClient(http, json,
                    baseUrl.isBlank() ? ("openrouter".equalsIgnoreCase(provider) ? "https://openrouter.ai/api/v1" : "https://api.openai.com/v1") : baseUrl,
                    apiKey, model, provider.toLowerCase(Locale.ROOT));
            default -> throw new IllegalStateException("payledger.ai.provider must be anthropic, openai-compatible, openrouter or none");
        };
    }

    @Bean
    SpeechToText speechToText(RestClient.Builder builder, JsonMapper json,
                              @Value("${payledger.ai.stt.base-url:https://api.openai.com/v1}") String baseUrl,
                              @Value("${payledger.ai.stt.api-key:}") String apiKey,
                              @Value("${payledger.ai.stt.model:gpt-4o-mini-transcribe}") String model) {
        RestClient.Builder http = builder.clone().requestFactory(ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults().withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(60))));
        return new SpeechToText(http, json, baseUrl, apiKey, model);
    }

    /** AI switched off: callers check {@link #provider()} first; a call anyway is a bug, so it says so. */
    static final class NoLlm implements LlmClient {
        @Override
        public Completion complete(String system, String user, int maxTokens) {
            throw new LlmException("AI is not configured");
        }

        @Override
        public String provider() {
            return "none";
        }

        @Override
        public String model() {
            return "none";
        }
    }
}
