# ADR-0004: Use the transactional outbox pattern for publishing Kafka events
**Date:** 03-09-2026
## Context
Given Kafka was chosen as the event transport in [[0003-kafka-for-internal-service-messaging]], `payment-api-service` now needs to get events onto a Kafka topic whenever it saves a transaction. Saving to Postgres and publishing to Kafka are two separate systems.
If the database commit succeed while the Kafka publish fails due to a crash or network issue → losing the event forever or the other way around.
## Decision
Write the event as a row in an outbox table, inside the same local database transaction as the transaction save, so either both happen or neither does. A separate poller process reads unpublished outbox rows and pushed them to Kafka, making each row as sent one the broker confirms.
## Alternatives considered
#### Option A - Naive direct publish with no pattern at all
**How it works**: call the Kafka publish immediately after the database save, in the same method, with no coordination between the two.
**Pros**: Simplest possible code, nothing extra to build or run
**Cons**: As described above, an event can be lost if the publish fails after the commit, or published for a transaction that later rolls back
#### Option B - Two-phase commit or a distributed transaction across Postgres and Kafka
**How it works**: A transaction coordinator makes sure the database write and the Kafka publish commit atomically together
**Pros**: theoretically closes the inconsistency window entirely
**Cons**: 
- Kafka doesn't support two-phase commit well, this adds real latency and introduces the coordinator itself as a new single point of failure.
#### Chosen - Transactional outbox pattern
**How it works**: as described above
**Pros**: guarantees the event corresponds to what's actually committed, using only a single local database transaction rather than a distributed one, and the outbox table doubles as an audit log of what was published and when
**Cons**: 
- Require writing and running a poller process, introduces a small delay between commit and publish rather than instant delivery
- Downstream consumers still need to be idempotent, since a poller crash between publishing and marking a row sent can still produce a duplicate.
## Consequences
**Gained**: event publication is guaranteed to correspond to what's actually committed in the database, without resorting to a distributed transaction, plus a built-in audit trail of what got published and when
**Gave up/new risk**: 
- A small delay between commit and publish because of the new poller that needs to be built and run and monitored.
- Downstream idempotency needed since at-least-once delivery still allows duplicates
**Revisit if**:
- Volume or latency requirements grow past what a simple polling can handle