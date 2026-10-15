package dev.payledger.core.ai;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Speech to text for browsers that can't do it themselves (Firefox, some Chromium forks). Chrome, Edge and Safari
 * transcribe live on the device for free; only the rest send a recording here. OpenAI-style /audio/transcriptions,
 * which OpenAI and Groq (Whisper) both offer. The audio is never stored.
 */
public class SpeechToText {

    private final RestClient http;
    private final JsonMapper json;
    private final String model;
    private final boolean enabled;
    private final String host;

    SpeechToText(RestClient.Builder builder, JsonMapper json, String baseUrl, String apiKey, String model) {
        this.http = builder.baseUrl(baseUrl).defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.json = json;
        this.model = model;
        this.enabled = !apiKey.isBlank();
        this.host = baseUrl.replaceAll("^https?://([^/]+).*$", "$1");
    }

    public boolean enabled() {
        return enabled;
    }

    public String model() {
        return model;
    }

    public String provider() {
        return host;
    }

    /** @param language a hint like "en" or "hi", or null to let it detect */
    public String transcribe(byte[] audio, String filename, String contentType, String language) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("model", model);
        if (language != null && !language.isBlank()) {
            form.add("language", language);
        }
        form.add("response_format", "json");
        form.add("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        String raw;
        try {
            raw = http.post().uri("/audio/transcriptions").contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new LlmClient.LlmException("speech to text: " + e.getMessage(), e);
        }
        JsonNode root = json.readTree(raw == null ? "{}" : raw);
        return root.path("text").asString("").strip();
    }
}
