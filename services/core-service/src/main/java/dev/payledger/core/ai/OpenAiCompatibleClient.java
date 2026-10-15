package dev.payledger.core.ai;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * The OpenAI-style chat completions API: OpenAI itself, OpenRouter (many providers' models behind one key), Google
 * Gemini's OpenAI endpoint, Groq, Together, or a local Ollama. JSON mode is requested; the answer is the first choice.
 */
final class OpenAiCompatibleClient implements LlmClient {

    private final RestClient http;
    private final JsonMapper json;
    private final String model;
    private final String provider;

    OpenAiCompatibleClient(RestClient.Builder builder, JsonMapper json, String baseUrl, String apiKey, String model, String provider) {
        this.http = builder.baseUrl(baseUrl).defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.json = json;
        this.model = model;
        this.provider = provider;
    }

    @Override
    public Completion complete(String system, String user, int maxTokens) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "temperature", 0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        String raw;
        try {
            raw = http.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new LlmException(provider + ": " + e.getMessage(), e);
        }
        JsonNode root = json.readTree(raw == null ? "{}" : raw);
        String text = root.path("choices").path(0).path("message").path("content").asString("");
        if (text.isBlank()) {
            throw new LlmException(provider + ": no text in the answer");
        }
        JsonNode usage = root.path("usage");
        return new Completion(text, usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0), root.path("model").asString(model));
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String model() {
        return model;
    }
}
