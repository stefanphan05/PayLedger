-- Written in the same database transaction as the transaction it describes,
-- so the two commit together or not at all.
-- See docs/decisions/0004-outbox-pattern-for-kafka-events.md
CREATE TABLE outbox_events (
   id             BIGSERIAL PRIMARY KEY,
   transaction_id UUID        NOT NULL,
   event_type     VARCHAR(50) NOT NULL,
   payload        TEXT        NOT NULL,
   created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
   published_at   TIMESTAMPTZ
);

-- published_at IS NULL means "still needs sending"; a timestamp means "sent, at this
-- time". The WHERE clause is the point: this index covers ONLY the unsent rows, so the
-- poller's lookup stays fast forever even as years of sent history pile up behind it.
CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;