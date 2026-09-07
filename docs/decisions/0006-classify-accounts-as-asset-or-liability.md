# ADR-0006: Classify accounts as ASSET or LIABILITY

**Date:** 07-09-2026

**Service:** ledger-service

## Context

Every user wallet starts at zero, so without a source of funds no payment can ever succeed. Money has to enter from somewhere, and that somewhere needs an account row because `ledger_entries.account_id` is a foreign key.
That funding account does not behave like a wallet. A `DEBIT` raises it while a `DEBIT` lowers a wallet, so one line of balance arithmetic cannot serve both without knowing which kind of account it is holding.

## Decision

An `account_class` column, `ASSET` or `LIABILITY`, with `normalBalance` on the enum deciding the direction. Customer wallets are liabilities, since the platform holds money it owes users. The funding account is the asset holding the cash.

## Alternatives considered

#### Option A - Treat every account the same, let the funding account go negative

**How it works**: `DEBIT` always subtracts, `CREDIT` always adds; funding sits at a large negative number

**Pros**: no class column, one arithmetic rule

**Cons**:
- `CHECK (balance >= 0)` has to be dropped, so real wallets lose overdraft protection too
- A flag is still needed to find the funding account and exempt it, which is the same column under a worse name

#### Option B - No funding account, set opening balances with `UPDATE`

**How it works**: seed wallets with a balance directly

**Pros**: simplest possible

**Cons**:
- `balance` no longer reconstructible from `ledger_entries`. The invariant is broken by the seed data itself, so a real bug and the fixtures look identical

## Consequences

**Gained**: one arithmetic rule covering both kinds of account; no account is ever legitimately negative so the database `CHECK` protects everything; assets minus liabilities per currency becomes a real health check.

**Gave up/new risk**:
- An extra concept, read in only two places today: the balance update, and finding the funding account
- Named `account_class`, not `account_type`, because retail banking uses "account type" for checking vs savings. Two axes with one name would confuse

**Revisit if**: fees are charged, which needs `REVENUE`, or the platform holds cash in more than one place, which means several ASSET rows.
