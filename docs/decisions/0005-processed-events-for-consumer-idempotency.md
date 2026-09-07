# ADR-0005: Guard against redelivery with a processed_events primary key

**Date:** 07-09-2026

**Service:** ledger-service

## Context

Kafka can deliver the same message twice (at-least-once delivery). Not a bug it's how "at-least-once" delivery works. If the consumer's ack gets lost, or a crash happens before the offset is saved, Kafka just resends the event. For most systems that's harmless. For a ledger service, processing the same "payment applied" event twice means the money moves twice.

## Decision

A `processed_events` table with the producer's `eventId` as the **primary key**, inserted in the same transaction as the entries and balances. A redelivery raises a unique violation and the whole transaction rolls back.

## Alternatives considered

#### Option A - Check first: `if (processedEvents.existsById(eventId)) return`

**How it works**: query the table, skip if the row exists

**Pros**: obvious to read, no exception handling

**Cons**:
- Check-then-act. Two deliveries can both pass the check before either commits
- It is an optimisation, not a guard. Only a constraint cannot be raced

#### Option B - Kafka exactly-once semantics

**How it works**: Kafka transactions tie the offset and the produced record together

**Pros**: no dedup table

**Cons**:
- Covers Kafka only. The Postgres write that actually moves money sits outside it, so the failure being defended against is not covered

#### Option C - Deduplicate on `transaction_id`

**How it works**: skip if entries already exist for that transaction

**Pros**: no extra id to carry

**Cons**:
- One transaction produces several events over its life. This cannot tell a redelivery from a genuinely new event about the same payment

## Consequences

**Gained**:
- Duplicate Kafka message deliveries cannot apply the same ledger update twice
- The database guarantees correctness through the primary key constraint, rather than relying on application logic.
- The solution remains safe even if multiple instances of the ledger service are running concurrently.

**Gave up/new risk**:
- Every processed event requires an additional row in the `processed_events` table
- The table will continue to grow over time and may eventually require an archival or cleanup strategy.
- Duplicate deliveries are detected by the database, so the application must handle the resulting unique-constraint exception gracefully.

**Revisit if**: the `processed_events` table becomes large enough that retention or cleanup is needed. Any pruning strategy must ensure old event IDs are not removed too early, otherwise an old Kafka redelivery could be processed again.
