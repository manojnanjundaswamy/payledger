package dev.payledger.core.ai;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/** Anthropic's Messages API (Claude). The answer is asked for as JSON in the prompt and read from the first text block. */
final class AnthropicClient implements LlmClient {

    private final RestClient http;
    private final JsonMapper json;
    private final String model;

    AnthropicClient(RestClient.Builder builder, JsonMapper json, String baseUrl, String apiKey, String model) {
        this.http = builder.baseUrl(baseUrl).defaultHeader("x-api-key", apiKey).defaultHeader("anthropic-version", "2023-06-01").build();
        this.json = json;
        this.model = model;
    }

    @Override
    public Completion complete(String system, String user, int maxTokens) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "temperature", 0,
                "system", system,
                "messages", List.of(Map.of("role", "user", "content", user)));
        String raw;
        try {
            raw = http.post().uri("/v1/messages").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new LlmException("Anthropic: " + e.getMessage(), e);
        }
        JsonNode root = json.readTree(raw == null ? "{}" : raw);
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asString(""))) {
                text.append(block.path("text").asString(""));
            }
        }
        if (text.isEmpty()) {
            throw new LlmException("Anthropic: no text in the answer (" + root.path("stop_reason").asString("?") + ")");
        }
        JsonNode usage = root.path("usage");
        return new Completion(text.toString(), usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0),
                root.path("model").asString(model));
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public String model() {
        return model;
    }
}
