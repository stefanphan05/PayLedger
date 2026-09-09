-- Every outbox row remembers the flow that created it, because the row is written on
-- the request thread but sent to Kafka a second later on the poller thread, which has
-- no memory of that request. See ADR-0011.
ALTER TABLE outbox_events ADD COLUMN correlation_id VARCHAR(64);

-- Rows written before this column existed. The transaction id is the sensible stand-in:
-- it is what you would have searched for anyway.
UPDATE outbox_events SET correlation_id = transaction_id::text WHERE correlation_id IS NULL;

ALTER TABLE outbox_events ALTER COLUMN correlation_id SET NOT NULL;