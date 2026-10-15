-- AI reading (docs/ai.md): quick add by voice or text, and a second opinion on bank messages the rules can't read.
-- Every call to a model is one row here, kept for traceability: who, what for, which provider and model, tokens,
-- what it cost, how long it took, what was sent (already masked) and what came back, and what we did with it.
-- The texts are cleared after the retention period (payledger.ai.text-retention); the numbers are kept.
create table ai_requests (
    id              uuid          primary key,
    user_id         uuid          references users (id) on delete set null,
    at              timestamptz   not null default now(),
    -- QUICK_ADD (typed or spoken), CAPTURE (an alert the rules couldn't read), TRANSCRIBE (speech to text)
    feature         varchar(20)   not null,
    -- TYPED, VOICE, EMAIL, PASTE, SHARE
    channel         varchar(12),
    provider        varchar(30)   not null,
    model           varchar(80)   not null,
    status          varchar(12)   not null check (status in ('OK', 'ERROR', 'INVALID', 'REFUSED')),
    error           varchar(500),
    input_tokens    integer       not null default 0,
    output_tokens   integer       not null default 0,
    -- Seconds of audio, for speech to text (billed by the minute).
    audio_seconds   numeric(8, 2),
    -- In millionths of a US dollar: providers price in dollars, and a call costs fractions of a cent.
    cost_micros     bigint        not null default 0,
    latency_ms      integer,
    -- What the model saw (after masking) and what it answered.
    input_text      text,
    output_text     text,
    -- Did it count against the monthly allowance (Free plan)?
    counted         boolean       not null default false,
    -- The model's own confidence (0..1), and ours after checking its answer against the text.
    model_confidence numeric(4, 3),
    confidence      numeric(4, 3),
    -- AUTO_ADDED, TO_REVIEW, NOT_A_TRANSACTION, FAILED
    outcome         varchar(20),
    entries         integer,
    captured_item_id uuid         references captured_items (id) on delete set null,
    transaction_ids uuid[]
);
create index ai_requests_user_at on ai_requests (user_id, at desc);
create index ai_requests_at on ai_requests (at desc);

-- How sure we were about a captured item, who read it, and whether it was added without asking.
alter table captured_items add column confidence numeric(4, 3);
alter table captured_items add column ai_request_id uuid references ai_requests (id) on delete set null;
alter table captured_items add column auto_added boolean not null default false;
alter table captured_items add column category_hint varchar(60);

-- Add bank alerts without asking when we're at least 95% sure (on by default; switch off on Auto-capture).
alter table users add column auto_add_captured boolean not null default true;
