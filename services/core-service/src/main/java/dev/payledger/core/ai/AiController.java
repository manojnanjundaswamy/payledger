package dev.payledger.core.ai;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/** Quick add by voice or text, and speech to text for browsers that can't transcribe on the device. */
@RestController
class AiController {

    /** About two minutes of compressed speech; longer isn't an entry. */
    private static final long MAX_AUDIO = 4 * 1024 * 1024;

    private final QuickAddService quickAdd;
    private final AssistantService assistant;
    private final AiReader reader;
    private final SpeechToText stt;
    private final AiAudit audit;
    private final AiConfig.Prices prices;

    AiController(QuickAddService quickAdd, AssistantService assistant, AiReader reader, SpeechToText stt, AiAudit audit, AiConfig.Prices prices) {
        this.quickAdd = quickAdd;
        this.assistant = assistant;
        this.reader = reader;
        this.stt = stt;
        this.audit = audit;
        this.prices = prices;
    }

    @GetMapping("/api/core/ai/status")
    @Operation(summary = "Is AI reading on, can the server transcribe speech, and this month's AI reads (Free's limit, or none on Premium)")
    QuickAddService.Status status(CurrentUser user) {
        return new QuickAddService.Status(reader.enabled(), stt.enabled(), quickAdd.usage(user));
    }

    record QuickAdd(String text, String channel) {
    }

    @PostMapping("/api/core/ai/quick-add")
    @Operation(summary = "Read what you typed or said into entries: at least 95% sure ones are added (and can be undone), the rest come back to check")
    QuickAddService.Result quickAdd(CurrentUser user, @RequestBody QuickAdd body) {
        return quickAdd.add(user, body.text(), "VOICE".equals(body.channel()) ? "VOICE" : "TYPED");
    }

    record Talk(java.util.List<AssistantService.Turn> turns, java.util.List<AssistantService.Shown> shown, String page, String channel) {
    }

    @PostMapping("/api/core/ai/assistant")
    @Operation(summary = "Voice mode: what was said becomes proposed actions (checked, not done), answers, a page to open, or a yes/no")
    AssistantService.Reply talk(CurrentUser user, @RequestBody Talk body) {
        return assistant.talk(user, body.turns(), body.shown(), body.page(), "TYPED".equals(body.channel()) ? "TYPED" : "VOICE");
    }

    @PostMapping(value = "/api/core/ai/transcribe", consumes = "multipart/form-data")
    @Operation(summary = "Speech to text for browsers without their own (the recording is never kept)")
    Map<String, String> transcribe(CurrentUser user, @RequestParam("audio") MultipartFile audio,
                                   @RequestParam(value = "seconds", required = false) Double seconds,
                                   @RequestParam(value = "language", required = false) String language) throws IOException {
        if (!stt.enabled()) {
            throw new SpeechOff();
        }
        if (audio.isEmpty() || audio.getSize() > MAX_AUDIO) {
            throw new BadAudio();
        }
        if (audit.lastMinute(user.id()) >= 20) {
            throw new AiReader.AiBusyException();
        }
        double secs = seconds == null ? 0 : Math.max(0, Math.min(seconds, 180));
        long started = System.nanoTime();
        String lang = language == null ? null : language.replaceAll("[^a-zA-Z]", "");
        try {
            String text = stt.transcribe(audio.getBytes(), audio.getOriginalFilename() == null ? "speech.webm" : audio.getOriginalFilename(),
                    audio.getContentType(), lang == null || lang.length() < 2 ? null : lang.substring(0, 2).toLowerCase(java.util.Locale.ROOT));
            audit.record(new AiAudit.Call(user.id(), "TRANSCRIBE", "VOICE", stt.provider(), stt.model(), "OK", null, 0, 0, secs,
                    prices.audioMicros(secs), (System.nanoTime() - started) / 1_000_000, null, text, false, null, null, null, null, null));
            return Map.of("text", text);
        } catch (LlmClient.LlmException e) {
            audit.record(new AiAudit.Call(user.id(), "TRANSCRIBE", "VOICE", stt.provider(), stt.model(), "ERROR", e.getMessage(), 0, 0, secs,
                    0, (System.nanoTime() - started) / 1_000_000, null, null, false, null, null, "FAILED", null, null));
            throw new AiReader.AiUnavailableException();
        }
    }

    static class SpeechOff extends ApiException {
        SpeechOff() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "speech-off", "speech to text isn't available here; type it instead");
        }
    }

    static class BadAudio extends ApiException {
        BadAudio() {
            super(HttpStatus.BAD_REQUEST, "bad-audio", "send up to about two minutes of audio");
        }
    }
}
