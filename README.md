# PayLedger
PayLedger is a miniature payment system designed like real-world fintech platforms to move money safely, prevent accidental duplicate charges, and keep financial records completely accurate. Instead of packing everything into one big program, it splits the job between two dedicated workers that pass notes to each other using a messaging pipeline called Kafka, plus a third that can explain what any of it did and why.

### payment-api-service
`payment-api-service` is the public API. When a user clicks "Pay $15", this service catches the request, makes sure a double-click doesn't create two charges and immediately hands back a receipt marked "PENDING". It doesn't move any actual money itself, it simply logs the intent in its database and drops a message into the pipeline saying a new payment needs processing.

### ledger-service
`ledger-service` acts as the private back-office accountant that never talks to the outside internet. It picks up that message, checks if the sender actually has enough funds, moves the balance using proper double-entry bookkeeping by taking that $15 from one account and adding $15 to the other. Once the money is safely moved, it sends a note back so the front door can officially flip the transaction status from "PENDING" to "COMPLETED".

### insights-service
`insights-service` is the one you can talk to. Ask it "why did transaction demo-8 fail?" and it finds that payment's actual log lines across both services and explains what happened, instead of you reading two sets of logs by hand. Ask it "why did you choose the outbox pattern?" and it answers from this repository's own design documents rather than guessing. It reads the system, it never changes anything in it.

## Running it

All you need is Docker. Every database, Redis, Kafka and all three services come up together.

```bash
git clone <repo-url> && cd payledger
cp .env.example .env
docker compose up --build
```

The payments API is then on `http://localhost:8080`. To stop everything: `docker compose down`.

`insights-service` is on `http://localhost:8000`, and needs two extra steps before it can
answer anything. Add a free [Gemini API key](https://aistudio.google.com/apikey) to your
`.env` as `GEMINI_API_KEY`, then give it something to read:

```bash
docker compose logs --no-log-prefix > insights-service/data/logs.jsonl
docker compose exec insights python -m app.ingestion.ingestion_cli
```

That second step is manual on purpose and has to be re-run to pick up new payments — see
[insights-retrieval.md](docs/insights-retrieval.md). Everything else works
without it; only `/insights/ask` depends on it.

## Documentation

- [API reference](docs/api.md) — every HTTP endpoint across both public services
- [Decisions](docs/decisions) — numbered ADRs across all three services
- [System design](docs/architecture.md) — the diagrams, and what each service does
- [Databases](docs/database.md) — what is stored, where, and which constraints matter
- [Testing](docs/testing.md) — what is tested, how to run it, and what is not covered
- [Failure modes](docs/failure-modes.md) — what breaks when each piece goes down
- [Observability](docs/observability.md) — finding one payment across both services
- [Insights retrieval](docs/insights-retrieval.md) — how a plain English question becomes an answer
- [Assumptions](docs/assumptions.md) — what this project simplifies on purpose

## Tech Stack & Architecture Roles

| Layer / Tool              | Technology              | Purpose in PayLedger                                                    |
| :------------------------ | :---------------------- | :---------------------------------------------------------------------- |
| **Language & Framework**  | Kotlin, Spring Boot 4   | Core microservices framework (`payment-api` & `ledger`)                 |
| **Insights Service**      | Python, FastAPI         | Question answering over the system's own logs and docs (`insights`)     |
| **Primary Datastores**    | PostgreSQL 16           | Separate, isolated DBs for transactions and double-entry ledger         |
| **Cache & Deduplication** | Redis 7                 | Distributed locking & atomic `SETNX` idempotency reservation            |
| **Event Broker**          | Apache Kafka 4 (KRaft)  | Asynchronous, decoupled event-driven communication                      |
| **Migrations**            | Flyway                  | Versioned, reproducible schema management (no auto-DDL)                 |
| **Auth & Access**         | Spring Security, JWT    | Stateless API authentication and token verification                     |
| **Integration Testing**   | Testcontainers, JUnit 5 | Ephemeral real-instance testing for Postgres and Kafka                  |
| **Vector Search**         | pgvector on Postgres 16 | Meaning-based search over logs and design docs, in the database already used |
| **Text Embeddings**       | sentence-transformers   | Turns text into comparable numbers locally, with no API key or network call |
| **Answer Generation**     | Google Gemini           | Writes the final answer from the retrieved sources only                 |
| **Orchestration**         | Docker Compose          | Local multi-service environment orchestration                           |