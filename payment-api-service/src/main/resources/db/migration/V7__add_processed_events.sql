CREATE TABLE processed_events (
  event_id UUID PRIMARY KEY,
  transaction_id UUID NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);