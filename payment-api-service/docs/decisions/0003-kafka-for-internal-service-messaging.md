# ADR-0003: Use Kafka as the event transport between payment-api-service and other services
**Date:** 03-09-2026
## Context
`payment-api-service` needs to tell other services that a transaction happened, without the two services being tightly coupled, and without losing that notification if something crashes halfway through.
## Decision
Choose Kafka as the asynchronous message broker connecting `payment-api-service` to other downstream services
1. `payment-api-service` publishes domain events (`payment.initiates`) to a topic
2. consumers subscribe independently rather than being called directly
## Alternatives considered
#### Option A - Synchronous REST API call to other services
**How it works**: `payment-api-service` calls other services directly via API and waits for a response before completing the client's request
**Pros**: Simple to reason about, immediate consistency, no extra infrastructure to run
**Cons**: 
- Couples the two services, if the other service slows or crashes, the payment request fails or hangs too
- Potentially adding some more consumers (e.g. notification service) means gotta go back and call it directly, changing the code
#### Option B - RabbitMQ
**How it works**: Pretty much the same concept as Kafka
**Pros**: Same as Kafka
**Cons**: 
- Once a message is consumes, it's generally gone, while ledger need to able to reprocess or audit past events
## Consequences
**Gained**: Loose coupling between services in time and availability, having a replayable event history, room to more consumers later without touching `payment-api-service`
**Gave up/new risk**: 
- Eventual consistency instead of immediate, the ledger update isn't instant.
- Potentially cause Loss/duplication. [[0004-outbox-pattern-for-kafka-events]] address this problem
**Revisit if**: the project's needs shrink to a single consumer with no audit/replay requirement, at which point RabbitMQ or a direct call would be simpler for the same outcome