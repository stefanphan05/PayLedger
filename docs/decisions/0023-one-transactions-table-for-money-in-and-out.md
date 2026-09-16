# ADR-0023: One transactions table for transfers, deposits and withdrawals

**Date:** 16-09-2026

**Service:** payment-api-service, ledger-service, fraud-service

## Context

Until now money could only move *between* users. There was no way to put money in or take it out, so balances were set by writing straight into `ledger-db.accounts` by hand. That is how the demo data was made, and it is what `assumptions.md` meant by "money only enters the system by hand".

The awkward part is who is on the other side. A transfer has two users. A deposit has only the user being paid; the other side is the account holding the platform's cash, which lives in `ledger-db` and is not a user at all. `transactions` required both parties, both pointing at `users`, and required them to be different people.

So the question was not how to move the money — the ledger has been able to do that since ADR-0006 — but where to record a payment that only has one user on it.

## Decision

Keep one `transactions` table. Add a `type` column saying `TRANSFER`, `DEPOSIT` or `WITHDRAWAL`, and let the party column on the platform's side be empty. `ledger-service` fills that empty side in with its own funding account for the currency.

## Alternatives considered

#### Option A - Separate `deposits` and `withdrawals` tables

**How it works**: two new tables, two new event types, their own outbox rows and their own consumers on the other side

**Pros**: `transactions` is not touched, so nothing already working can break

**Cons**:
- The whole path from request to settlement gets written twice more. That path is the careful part of this system: idempotency, the outbox, the fraud gate, the correlation id, the status round trip
- A user's money history is split across three endpoints, so "what happened to my account" can no longer be answered by one query

#### Option B - A fake "house user" per currency

**How it works**: insert a user row whose id matches the funding account, and record a deposit as an ordinary transfer from it

**Pros**: no schema change at all, and every existing query keeps working untouched

**Cons**:
- Puts something that is not a person in the `users` table, with an email address and a password hash, which anything listing or counting users then has to know to skip
- It reads as a transfer in the data, so the one question worth asking of these rows — did money enter the system or move inside it — can no longer be asked

## Consequences

**Gained**: deposits and withdrawals ride the pipeline that already exists, so they get idempotency, the fraud gate, correlation ids and the settlement round trip without any of it being written again. They appear in `GET /transactions` next to transfers, and the type column makes money entering the system tellable from money moving inside it.

**Gave up/new risk**:
- `senderId` and `recipientId` can now be null in an API response and in a published event. Every reader has to cope with that, and the check constraint is the only thing making sure the empty one is the right one
- Deposits skip fraud screening. A deposit has no sender, and every rule asks what one sender has been doing recently, so there is nobody to ask about — and nothing to catch either, since the rules look for an account being emptied. Withdrawals are still screened, and they are the ones where money actually leaves
- `ledger-service` refuses a deposit or withdrawal in a currency it holds no cash in, rather than inventing an account. That is a new way for a payment to fail

**Revisit if**: money starts arriving from a real card or bank provider, which brings a reference to their side of it and a state before `PENDING`; or the platform holds cash in more than one place per currency, which makes "the funding account for this currency" the wrong question.
