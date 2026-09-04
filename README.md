# PayLedger

PayLedger is a payment processing and double-entry ledger system, built as independent
Kotlin/Spring Boot services that talk asynchronously over Kafka, each owning its own
Postgres database. It is designed around the constraints that make distributed payments
hard rather than around CRUD: money has to move exactly once even when clients retry,
brokers redeliver, and services restart — so every hop is idempotent and every state
change is durable in the database *before* it is announced to anyone else. The shape of
the system is what lets it absorb load horizontally: stateless API instances behind a
shared Redis, a partitioned event log between services, and event-driven workers that
scale independently of the request path.
## Stack

Kotlin · Spring Boot 4 · Postgres 16 · Redis 7 · Apache Kafka 4 (KRaft) · Flyway ·
Spring Security (JWT) · JUnit 5 + Testcontainers · k6 · Docker Compose
