package dev.payledger.core.ai;

/**
 * A language model, whoever runs it. Two adapters cover the market: Anthropic's Messages API, and the OpenAI-style
 * chat completions API that OpenAI, OpenRouter, Google Gemini, Groq, Together and local servers (Ollama) all speak.
 * Which one, and which model, is configuration (payledger.ai.*), so switching provider is a restart, not a release.
 */
public interface LlmClient {

    /** @return the model's answer; it was asked for one JSON object */
    Completion complete(String system, String user, int maxTokens);

    /** "anthropic", "openai-compatible", or "none" */
    String provider();

    String model();

    /** @param text the answer as given; the tokens as the provider counted them */
    record Completion(String text, int inputTokens, int outputTokens, String model) {
    }

    /** The provider didn't answer usefully (down, slow, over its limit, refused the key). */
    class LlmException extends RuntimeException {
        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }

        public LlmException(String message) {
            super(message);
        }
    }
}
