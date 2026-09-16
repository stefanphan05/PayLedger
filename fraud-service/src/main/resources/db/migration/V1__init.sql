CREATE TABLE payment_attempts (
    transaction_id   UUID PRIMARY KEY,
    sender_id        UUID          NOT NULL,
    recipient_id     UUID          NOT NULL,
    amount           NUMERIC(19,4) NOT NULL,
    currency         CHAR(3)       NOT NULL,
    screened_at      TIMESTAMPTZ   NOT NULL,

    decision         TEXT          NOT NULL,   -- ALLOW | REVIEW | BLOCK
    score            INT           NOT NULL,
    triggered_rules  JSONB         NOT NULL,   -- [{"rule":"VELOCITY","weight":40,"detail":"..."}]

    -- Set when an admin overturns a REVIEW. Null means the rules had the last word.
    overridden_at    TIMESTAMPTZ,
    overridden_to    TEXT,                     -- ALLOW | BLOCK

    correlation_id   VARCHAR(64)   NOT NULL,

    CONSTRAINT check_decision CHECK (
        decision IN ('ALLOW', 'REVIEW', 'BLOCK')
    ),

    CONSTRAINT check_score_range CHECK (
        score BETWEEN 0 AND 100
    )
);

-- The velocity, fan-out and amount-anomaly rules all read one sender's recent rows.
CREATE INDEX
    payment_attempts_sender_time_idx
ON
    payment_attempts (sender_id, screened_at DESC);

-- "has this sender ever paid this recipient before" — the new-recipient rule.
CREATE INDEX
    payment_attempts_pair_idx
ON
    payment_attempts (sender_id, recipient_id);

-- The review queue reads a tiny slice of a big table, so it gets a partial index
-- rather than a scan.
CREATE INDEX
    payment_attempts_review_idx
ON
    payment_attempts (screened_at DESC)
WHERE
    decision = 'REVIEW';

-- Copied unchanged from ledger-service.
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );

CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    transaction_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;