# ADR-0008: Publish ledger verdicts to a separate ledger-events topic

**Date:** 07-09-2026

**Service:** ledger-service

## Context

`ledger-service` has to report what it decided so `payment-api-service` can move a transaction off PENDING. A topic already exists: `payment-events`, produced by the API and consumed by the ledger. The question is whether the verdict goes back onto it.

## Decision

A new topic, `ledger-events`, produced only by `ledger-service` and keyed by transaction id. One producer per topic.

## Alternatives considered

#### Option A - Publish verdicts back onto `payment-events`

**How it works**: one topic carries a payment's whole lifecycle

**Pros**: every event for one payment lands on the same partition, so the full lifecycle stays ordered

**Cons**:
- Both services would produce to it and both consume from it, so each has to filter out its own events
- A filtering mistake means a service consuming what it just published. With the ledger that is a loop that writes to the database

#### Option B - A topic per event type: `payment.completed`, `payment.failed`

**How it works**: consumers subscribe to exactly the outcome they care about

**Pros**: no filtering needed at all

**Cons**:
- Kafka orders only within a partition of one topic, so splitting outcomes across topics loses the order between the stages of one payment
- Three topics to create, monitor and keep in step. This is what earlier planning docs specified, before [[0003-kafka-for-internal-service-messaging]] settled on a single topic with the type in the envelope

## Consequences

**Gained**: a service can never consume its own output, so no filtering rule stands between correctness and a loop. Feature 07's consumer subscribes to one topic and handles everything on it.

**Gave up/new risk**:
- No ordering between a payment's initiation and its verdict, since they are on different topics. Acceptable because the verdict carries the transaction id and the consumer is idempotent

**Revisit if**: something needs a single ordered stream of a payment's full lifecycle, e.g. an audit or replay tool.
