# Assumptions and limitations
What this project simplifies on purpose, so "what are the limits of your system?" has a real answer.
## Ledger
* Only ASSET and LIABILITY account classes. No EQUITY, REVENUE or EXPENSE, so the platform cannot earn anything. REVENUE arrives with the first fee.
* Balances are stored on `accounts`, not summed from `ledger_entries`. Faster to read, but the two can drift if a bug writes one without the other.
* Every transaction is exactly two entries. Real ledgers allow any number that sum to zero (a fee makes three).
* Accounts are created lazily at zero balance the first time an event names them. The ledger has no user list, so an unknown id means "not seen yet", not "invalid".
* No overdrafts. Every balance is checked before applying, and the database blocks negatives.
* No FX. Sender currency, recipient currency and payment currency must all match. Revisit in the future when we want to support FX
* `NUMERIC(19,4)` assumes every currency has 4 decimal places. JPY has 0.
* One funding account per currency (AUD and USD), by convention only. Nothing stops a second one being created.
* Money only enters the system by hand, via a manual balanced insert. The deposit route potentially considered in the future
## Messaging
* Kafka delivers at least once, never exactly once. Exactly once would need the broker and Postgres to commit together, which is a distributed transaction.
* Duplicates are handled by the `processed_events` primary key, inserted in the same database transaction as the entries.
* Ordering only holds inside one partition. Events are keyed by transaction id, so one payment's events stay in order. Different payments have no order between them.
* `eventType` is a String, and unknown types are ignored rather than failing. Unknown JSON fields are ignored too.
* A rejected payment is final. `INSUFFICIENT_FUNDS` is never retried, even if the account is funded a second later. Retrying means making a new payment.
* One consumer thread. All 3 partitions are handled sequentially, so the `version` column on `accounts` is not exercised yet.
* The ledger publishes its verdict to `ledger-events` through its own outbox. `payment-api-service` is the only consumer.
* `PAYMENT_STATUS_CHANGED` is published but nothing reads it. It is left in place on purpose: it is the hook a notification service would subscribe to, and a record of every status change. The ledger ignores it, so it cannot loop back.

## Services
* No shared database and no shared code. The event DTOs are duplicated in
  `ledger-service` on purpose: the contract is the JSON, not a Kotlin class.
* The ledger ignores the `status` field the API sends. It decides the outcome itself.
* A payment reaches COMPLETED or FAILED only after the ledger has decided. Until then it stays PENDING, so clients must poll.
* The ledger's verdict wins. It overwrites whatever status the transaction had, including one an admin set by hand.
* The rejection reason is stored as plain text, not an enum. The API never has to know the ledger's full list of reasons.
* A verdict naming a transaction the API does not have is logged and dropped. No amount of retrying would make it exist.
## Infrastructure
* Single node everything. One Postgres per service, one Kafka broker with replication factor 1, one Redis.
* Local development only. The root `.env` holds throwaway credentials.
* Both services run on the host, not in compose. That is why `ledger-db` uses port 5433.
* The database is called `payment_db`, not `payments_db` as older docs said.
## Known gaps
Two things below are real bugs waiting to happen, not accepted trade offs.
* **No currency validation.** A EUR payment creates EUR wallets even though no EUR funding account exists. Harmless today (it gets rejected for insufficient funds), but once deposits work you could have EUR owed to users with no EUR held. Fix: reject when no ASSET account exists for that currency.
* **No error handler.** A message that cannot be read is retried ten times and then dropped, with nothing kept to look at afterwards. Fix is a dead letter topic in feature 08.
## Health check

Balances must reconcile per currency. Summing across currencies adds AUD to USD and means nothing.

```sql
SELECT currency,
       COALESCE(SUM(balance) FILTER (WHERE account_class='ASSET'), 0)
     - COALESCE(SUM(balance) FILTER (WHERE account_class='LIABILITY'), 0) AS diff
FROM accounts GROUP BY currency;   -- every row must be 0
```

## Not started
Resilience (08), observability (10), concurrency hardening (11), insights service (13).
Not assumptions, just work not begun.
