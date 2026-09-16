ALTER TABLE transactions ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'TRANSFER';

ALTER TABLE transactions ALTER COLUMN type DROP DEFAULT;

ALTER TABLE transactions ALTER COLUMN sender_id DROP NOT NULL;
ALTER TABLE transactions ALTER COLUMN recipient_id DROP NOT NULL;

ALTER TABLE transactions DROP CONSTRAINT chk_transactions_distinct_parties;
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_parties_match_type CHECK (
    (type = 'TRANSFER'   AND sender_id IS NOT NULL AND recipient_id IS NOT NULL AND sender_id <> recipient_id)
    OR (type = 'DEPOSIT'    AND sender_id IS NULL     AND recipient_id IS NOT NULL)
    OR (type = 'WITHDRAWAL' AND sender_id IS NOT NULL AND recipient_id IS NULL)
);