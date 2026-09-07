# PayLedger
PayLedger is a miniature payment system designed like real-world fintech platforms to move money safely, prevent accidental duplicate charges, and keep financial records completely accurate. Instead of packing everything into one big program, it splits the job between two dedicated workers that pass notes to each other using a messaging pipeline called Kafka.

### payment-api-service
`payment-api-service` is the public API. When a user clicks "Pay $15", this service catches the request, makes sure a double-click doesn't create two charges and immediately hands back a receipt marked "PENDING". It doesn't move any actual money itself, it simply logs the intent in its database and drops a message into the pipeline saying a new payment needs processing.

### ledger-service
`ledger-service` acts as the private back-office accountant that never talks to the outside internet. It picks up that message, checks if the sender actually has enough funds, moves the balance using proper double-entry bookkeeping by taking that $15 from one account and adding $15 to the other. Once the money is safely moved, it sends a note back so the front door can officially flip the transaction status from "PENDING" to "COMPLETED".

## Documentation

- [API reference](docs/api.md) — every endpoint on `payment-api-service`
- [Decisions](docs/decisions) — numbered ADRs across both services

## Tech Stack & Architecture Roles

| Layer / Tool              | Technology              | Purpose in PayLedger                                                    |
| :------------------------ | :---------------------- | :---------------------------------------------------------------------- |
| **Language & Framework**  | Kotlin, Spring Boot 4   | Core microservices framework (`payment-api` & `ledger`)                 |
| **Primary Datastores**    | PostgreSQL 16           | Separate, isolated DBs for transactions and double-entry ledger         |
| **Cache & Deduplication** | Redis 7                 | Distributed locking & atomic `SETNX` idempotency reservation            |
| **Event Broker**          | Apache Kafka 4 (KRaft)  | Asynchronous, decoupled event-driven communication                      |
| **Migrations**            | Flyway                  | Versioned, reproducible schema management (no auto-DDL)                 |
| **Auth & Access**         | Spring Security, JWT    | Stateless API authentication and token verification                     |
| **Integration Testing**   | Testcontainers, JUnit 5 | Ephemeral real-instance testing for Postgres and Kafka                  |
| **Orchestration**         | Docker Compose          | Local multi-service environment orchestration                           |