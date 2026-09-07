# ADR-0007: Store balances on accounts instead of deriving them from entries

**Date:** 07-09-2026

**Service:** ledger-service

## Context

`ledger_entries` is append-only and never shrinks, growing by two rows per payment forever. Every payment has to check the sender's balance before it can be authorised, so that read is on the hot path.

## Decision

A `balance` column on `accounts`, updated in the same transaction as the entries that caused the change.

## Alternatives considered

#### Option A - Derive with `SUM(ledger_entries)` on every read

**How it works**: no stored balance, compute it when needed

**Pros**: single source of truth, cannot drift, self-healing after a bad write

**Cons**:
- Cost grows with history, on the one query every payment depends on. An account gets slower to use the more it is used

#### Option B - A materialised view refreshed periodically

**How it works**: derived, but cached by the database

**Pros**: fast reads, still computed from the log

**Cons**:
- Stale by definition. Authorising against a stale balance is how the same money gets spent twice

## Consequences

**Gained**: authorisation is a single row read regardless of history. It also gives `CHECK (balance >= 0)` and the `@Version` optimistic lock somewhere to live.

**Gave up/new risk**:
- Two sources of truth. The column and the entry log can disagree if a bug writes one without the other. Mitigated by writing both in one transaction, and detectable with the per-currency reconciliation query in [[assumptions]]

**Revisit if**: reconciliation ever comes out non-zero, which means the mitigation is not holding and deriving is worth its cost.
