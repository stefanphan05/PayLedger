-- The account money enters and leaves the system through. Fixed UUIDs so the
-- funding side of a deposit is findable without a lookup by class.
-- User wallets are not seeded here: their ids are minted by payment-api-service,
-- so this migration cannot know them. The ledger creates them on first sight.
INSERT INTO accounts (id, account_class, currency, balance) VALUES
    ('00000000-0000-0000-0000-000000000001', 'ASSET', 'AUD', 0),
    ('00000000-0000-0000-0000-000000000002', 'ASSET', 'USD', 0);