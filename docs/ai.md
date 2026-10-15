# AI reading

PayLedger uses a language model in two places, always behind our own checks:

1. **Say it or type it (quick add).** "450 petrol yesterday on HDFC card", "got 50k salary", "200 auto and 350 lunch".
2. **Bank messages the rules can't fully read** (forwarded emails, pasted or shared SMS).

Everything else (budgets, reports, the ledger) never touches a model.

## How a message is read

```
bank message ──► rules (free, instant) ──► all of amount, direction, date, payee? ──► yes: done
                                                     │ no
                                                     ▼
                                     AI (masked, logged, 1 of the month's AI reads)
                                                     │
                       compare with the rules ◄──────┘  (disagree: confidence drops to 60%)
                                                     │
                          our account match (last digits, or the only account at that bank)
                                                     │
                 ≥ 95% sure, account known, not a duplicate, auto-add on ──► added, with a bell and Undo
                 otherwise ─────────────────────────────────────────────► To review, filled in
```

- **Rules confidence.** Amount and direction found: 70%. A date in the text: +8. A payee: +8. An account we can match: +12.
  Read with a pattern you taught it: +5. All of them: 98%.
- **AI confidence.** The model's own, then lowered by our checks: the amount must appear in the text (else ≤ 50%), the date
  must be within the last ~13 months and not in the future (else ≤ 70%), another currency (≤ 90%), no matching account (≤ 90%).
- **Not a transaction.** If the AI says an email isn't one (OTP, offer, decline) and the rules found nothing, it is skipped
  and logged as such. If the rules saw a transaction and the AI didn't, a person decides.
- **Quick add** works the same way: ≥ 95% with a named account and no near-duplicate is added at once (tagged `ai`) and
  can be undone; everything else comes back as a filled-in draft that says why it wasn't added ("Which account? You didn't say.").
- **Without AI** (no key, or the Free allowance used) quick add still offers a simple reading of the amount and words,
  as a draft, and capture behaves as before.

## Privacy: what the model sees

`Masker` runs before anything leaves our server. It replaces:

| Replaced | With |
|---|---|
| Account and card numbers (XX1180, **1234, "account ending 0474", 4111 1111 …) | `[ACCOUNT]`, `[CARD]` |
| Reference and other long numbers (not amounts) | `[NUMBER]` |
| Phone numbers, email addresses | `[PHONE]`, `[EMAIL]` |
| Personal UPI ids (`9876543210@ybl`, `asha.k@oksbi`, masked ones) | `[UPI ID]` (merchant handles like `swiggy@icici` stay) |
| The person's own name ("MANOJ M N") | `[YOU]` (lets the model spot a transfer to yourself) |

The model also gets the names of the person's categories, and for quick add their account names (with digits masked:
"Axis 1180" becomes "Axis •••"). For bank messages it gets no account names at all: we match the account ourselves from
the unmasked text. No balances, no amounts other than the one in the text, no history.

Use a provider that doesn't train on API data and keeps it briefly (Anthropic and OpenAI's APIs don't train on it by
default; OpenRouter can be limited to zero-retention providers).

## Logs: every call is traceable

Table `ai_requests`, one row per call, written in its own transaction (a call that cost money is on record even if what
it was for fails):

- who (`user_id`), when (`at`), why (`feature`: QUICK_ADD, CAPTURE, TRANSCRIBE; `channel`: TYPED, VOICE, EMAIL, PASTE, SHARE)
- provider, model, `status` (OK, ERROR, INVALID), error
- `input_tokens`, `output_tokens`, `audio_seconds`, `cost_micros` (millionths of a US dollar, from the configured prices), `latency_ms`
- `input_text` (exactly what was sent, already masked) and `output_text` (the answer)
- `counted` (used one of the month's Free AI reads), `model_confidence`, `confidence` (after our checks)
- `outcome` (AUTO_ADDED, PARTLY_ADDED, TO_REVIEW, NOT_A_TRANSACTION, FAILED), `entries`, `captured_item_id`, `transaction_ids`

**Retention.** Texts are cleared after `AI_TEXT_RETENTION` (default 180 days) by a daily job; tokens, cost and outcome are
kept. Deleting an account clears its texts and unlinks the user (the anonymous cost row stays for the books). A person's
own AI log is in their data export (`aiRequests`), not restored on import.

**Admin → AI usage**: cost, requests, tokens and outcomes for today / 7 days / this month; by feature, by model, by person
(most expensive first), per day; and every request, expandable to the masked text sent and the answer. CSV export
(`/api/core/admin/export/ai.csv`) is audited like the other exports. Totals need the analytics permission; texts need audit.

**Limits.** Free: 25 AI reads a month (quick add and bank messages) and 10 other AI uses (for future features); staff can
change both in Admin → Settings. Premium: unlimited. Whatever the plan: 20 AI calls a minute per person, at most 10 AI
reads per paste, 4,000 characters per text.

## Voice

- **Chrome, Edge, Safari, Samsung Internet**: the browser's own speech recognition, live, on the device, free. Words
  appear as you speak; the language follows the profile's locale (en-IN, hi-IN, …).
- **Firefox and browsers whose recognition fails (Brave)**: we record, and the server turns the recording into text once
  you stop (OpenAI-style `/audio/transcriptions`: OpenAI or Groq), if `STT_API_KEY` is set. The recording is never stored;
  the call is logged (seconds, cost). Without a key those browsers show "type it instead".
- After speaking, the text is editable; on Add the model also fixes spelling, grammar and spoken numbers and shows
  "We heard: …".

## Choosing and switching the model

Provider-independent by configuration (`.env`), no code change:

| Option | Settings |
|---|---|
| Claude, direct (recommended to start: one key, good at this) | `AI_PROVIDER=anthropic`, `AI_MODEL=claude-haiku-5-5`, `AI_API_KEY=…` |
| OpenRouter (many providers and models behind one key, switch anytime) | `AI_PROVIDER=openrouter`, `AI_MODEL=anthropic/claude-haiku-5-5` (or any listed) |
| Anything OpenAI-compatible (OpenAI, Gemini, Groq, Together, local Ollama) | `AI_PROVIDER=openai-compatible`, `AI_BASE_URL=…/v1`, `AI_MODEL=…` |

Set `AI_PRICE_INPUT` / `AI_PRICE_OUTPUT` (US$ per million tokens) to the model's prices so the cost log is right.
A typical read is ~600 tokens in and ~120 out: about $0.0012 (₹0.10) on a Haiku-class model.

## Code

- `core-service/.../ai/`: `LlmClient` (+ `AnthropicClient`, `OpenAiCompatibleClient`), `SpeechToText`, `Masker`, `AiReader`
  (prompt, answer checks, confidence), `AiAudit` (log, retention), `AiContexts`, `QuickAddService`, `AiController`
- `CaptureService.decide` (rules first, AI second), `autoAdd`, `undo`; `PlanService.Limit.AI` / `AI_EXTRAS`
- `AdminAiController` (summary, requests), CSV export kind `ai`
- Web: `components/QuickAdd.tsx` (the sheet), `QuickAddBar.tsx` (dashboard), `hooks/useDictation.ts`, `state/ai.ts`,
  `pages/admin/AdminAi.tsx`; Auto-capture page: auto-add switch, "On its own" badge, Undo
- Tests: `MaskerTest`, `AiReaderTest`, `AiIntegrationTest` (scripted model: quick add, allowance, capture by AI, undo,
  masking, admin), `e2e/quick-add.spec.ts`
