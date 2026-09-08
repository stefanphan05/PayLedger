CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    account_class VARCHAR(20) NOT NULL,
    currency CHAR(3) NOT NULL,

    balance NUMERIC(19,4) NOT NULL DEFAULT 0,

    -- Optimistic locking. Two events touch one account must not interleave or read-modify-write
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT check_account_class CHECK (account_class IN ('ASSET', 'LIABILITY')),
    CONSTRAINT check_balance_non_negative CHECK (balance >= 0)
);

-- The immutable record of every movement. Append-only: correcting a mistake means
-- writing a reversing pair, never updating or deleting row. That is what makes
-- this an audit trail rather than a cache of balances.
CREATE TABLE ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    transaction_id UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts(id),

    -- Effect depends on the account's class: DEBIT raises an ASSET and lowers a
    -- LIABILITY, CREDIT does the reverse. Sign lives here, so amount is positive.
    direction VARCHAR(6) NOT NULL,  -- DEBIT | CREDIT
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT check_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT check_amount_positive CHECK (amount > 0)
);

-- show me everything that happened to this payment
-- show me this account's history
-- are the only two ways this table is ever read
CREATE INDEX idx_ledger_entries_transaction ON ledger_entries (transaction_id);
CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id);

-- The idempotency guard for the consumer side
--
-- Kafka delivers at-least-once: a producer retry after a lost ack outs the same
-- eventId on the topic twice, and a redelivery after a lost offset commit replays
-- one already applied. event_id as the PRIMARY KEY is the entire mechanism, the
-- insert happens in the same transaction as the entries, so a duplicate raises a
-- unique violation and rolls the whole apply back instead of double-spending
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The ledger publishes its own events, so it has the same dual-write problem the
-- API solved in ADR-0004: the balance update commits, the process dies before the
-- Kafka send, and nobody ever learns the payment completed. Same fix, same shape
CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    transaction_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

-- Covers only unsent rows, so the poller's lookup stays fast forever
-- even there's a lot of published events
CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;
