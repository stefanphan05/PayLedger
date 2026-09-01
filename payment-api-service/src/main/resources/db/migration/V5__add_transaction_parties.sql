ALTER TABLE transactions ADD COLUMN sender_id UUID;
ALTER TABLE transactions ADD COLUMN recipient_id UUID;

-- transactions predate user accounts; there are no parties to attribute them to
DELETE FROM transactions WHERE sender_id IS NULL OR recipient_id IS NULL;

-- make sure the sender and recipient are always set for future transactions
ALTER TABLE transactions ALTER COLUMN sender_id SET NOT NULL;
ALTER TABLE transactions ALTER COLUMN recipient_id SET NOT NULL;

ALTER TABLE transactions ADD CONSTRAINT fk_transactions_sender
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE RESTRICT;
ALTER TABLE transactions ADD CONSTRAINT fk_transactions_recipient
    FOREIGN KEY (recipient_id) REFERENCES users(id) ON DELETE RESTRICT;

-- reject any intern or update where the sender and recipient are the same user
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_distinct_parties
    CHECK (sender_id <> recipient_id);

CREATE INDEX idx_transactions_sender ON transactions (sender_id, created_at DESC);
CREATE INDEX idx_transactions_recipient ON transactions (recipient_id, created_at DESC);