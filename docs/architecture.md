# System Design

What PayLedger is made of, how one payment moves through it, and what each service is responsible for.

## Component diagram

Every moving part of PayLedger, and what talks to what.

![](../assets/component-diagram.png)

## Sequence diagrams
### Authentication

Every payments endpoint needs a token. Only `/auth/signup`, `/auth/login` and the health and metrics endpoints are open.

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Filter as JwtAuthenticationFilter
    participant AuthCtl as AuthController
    participant AuthSvc as AuthService
    participant AuthMgr as AuthenticationManager
    participant UserLoader as AppUserDetailsService
    participant UserDB as Payments DB (users)

    Client->>AuthCtl: POST /auth/signup
    AuthCtl->>AuthSvc: email trimmed + lowercased,<br/>password BCrypt hashed
    AuthSvc->>UserDB: saveAndFlush
    alt email already registered
        UserDB-->>AuthSvc: unique constraint violation
        AuthSvc-->>Client: 409 Email is already registered
    else new user
        AuthSvc-->>Client: 201 Created - no token yet
    end

    Client->>AuthCtl: POST /auth/login
    AuthCtl->>AuthSvc: email + password
    AuthSvc->>AuthMgr: authenticate
    AuthMgr->>UserLoader: loadUserByUsername(email)
    UserLoader->>UserDB: findByEmail
    alt wrong email or password
        AuthMgr-->>Client: 401 Invalid email or password
    else correct
        AuthSvc-->>Client: JWT (userId + email) + expiresIn 3600
    end
```

Signing up does not log you in, it returns the user, not a token, so the client has to call `/auth/login` afterwards in order to get a token.

### Payment flow
```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant PayAPI as payment-api-service
    participant Redis as Redis
    participant PayDB as Payments DB
    participant Poller1 as Outbox Relay (payment)
    participant Kafka as Apache Kafka
    participant Ledger as ledger-service
    participant LedgerDB as Ledger DB
    participant Poller2 as Outbox Relay (ledger)

    Client->>PayAPI: POST /transactions<br/>Idempotency-Key, X-Correlation-Id (optional)
    Note over PayAPI: 1. Correlation id generated if absent.<br/>Held for the life of the request only

    PayAPI->>Redis: SETNX user + key + body hash
    Redis-->>PayAPI: claimed (a repeat is replayed instead)

    PayAPI->>PayDB: INSERT transaction (PENDING)<br/>+ outbox_events (correlation_id)
    Note over PayDB: 2. Written down, because the<br/>request ends before the send
    PayAPI->>Redis: store the response against the key
    PayAPI-->>Client: 201 PENDING

    PayDB-->>Poller1: Poll outbox_events (every 1s)
    Poller1->>Kafka: Produce payment-events: PAYMENT_INITIATED<br/>+ header X-Correlation-Id
    Note over Poller1: 3. A different thread. Reads the<br/>id from the row, not from memory

    Kafka->>Ledger: Consume payment-events
    Note over Ledger: 4. Reads the header back off<br/>the record
    Ledger->>LedgerDB: INSERT processed_events + 2 entries<br/>+ UPDATE balances + INSERT outbox
    Note over LedgerDB: 5. One transaction. Same id continues<br/>onto the ledger's outgoing message

    LedgerDB-->>Poller2: Poll ledger outbox
    Poller2->>Kafka: Produce ledger-events:<br/>PAYMENT_COMPLETED or PAYMENT_FAILED

    Kafka->>PayAPI: Consume ledger-events
    PayAPI->>PayDB: INSERT processed_events<br/>+ UPDATE status / failure_reason + INSERT outbox
    Note over PayAPI: Same id the request started with,<br/>1.6 seconds and four threads later

    PayDB-->>Poller1: Poll outbox_events
    Poller1->>Kafka: Produce payment-events: PAYMENT_STATUS_CHANGED
```

### Monitoring and insights

```mermaid
sequenceDiagram
    autonumber
    participant Prom as Prometheus
    participant PayAPI as payment-api-service
    participant Ledger as ledger-service
    actor Operator
    participant Corpus as The corpus
    participant Insights as insights-service
    participant Gemini as Google Gemini

    loop every 15s
        Prom->>PayAPI: GET /actuator/prometheus
        Prom->>Ledger: GET /actuator/prometheus
    end
    Note over Prom: payledger_outbox_oldest_age_seconds<br/>is the number worth watching

    Operator->>Corpus: docker compose logs > logs.jsonl
    Operator->>Insights: run the ingestion
    Insights->>Corpus: read logs + docs
    Note over Insights: By hand. Payments made since the last<br/>dump are invisible, and it cannot say so

    Operator->>Insights: POST /insights/ask "why did demo-8 fail?"
    Insights->>Insights: exact id lookup + closest 3 by meaning
    Insights->>Gemini: the question + only what won
    Gemini-->>Insights: answer with [n] markers
    Insights-->>Operator: answer + the sources it used
```

## Services
### 1. payment-api-service

This service records the intent, guarantees a double-click cannot become two charges, and answers `PENDING` straight away. It doesn't move money.

A payment is claimed in Redis before any work starts, on user + `Idempotency-Key` + a hash of the body. The same key with a different body is a `409`, not a second payment ([ADR-0002](decisions/0002-redis-backed-idempotency-keys.md)).

Failures split in two. A deterministic rejection is stored and replayed, so a retry gets back the same `400` it got the first time. Anything else, a dropped connection at commit, is ambiguous, and the key is held for 60s rather than released, because an exception is not proof that nothing was written.

If the payment commits but its idempotency record does not, the client still gets its receipt. A `500` there would report failure about money that has already moved.

Nothing is sent to Kafka inside the request. The controller writes an outbox row and a poller sends it a second later ([ADR-0004](decisions/0004-outbox-pattern-for-kafka-events.md)), so a broker outage delays an accepted payment instead of losing it.

### 2. ledger-service

No public API at all, the only way in is a Kafka message.

Everything commits together or not at all. `processed_events` is why the same message delivered twice only moves money once ([ADR-0005](decisions/0005-processed-events-for-consumer-idempotency.md)).

It has no user directory. An unknown account id means *not seen yet*, not invalid, so it opens a wallet at zero, and the payment that created it is then rejected for insufficient funds.

Three things reject a payment ([ADR-0009](decisions/0009-store-the-rejection-reason-as-opaque-text.md)):

- `CURRENCY_MISMATCH`
- `SELF_TRANSFER`
- `INSUFFICIENT_FUNDS`

All three still publish an event, a refusal is an answer, and the `payment-api-service` is holding a `PENDING` row waiting for one.

### 3. insights-service

Read-only. It searches first, and shows the model only what won.

It never searches one way. Identifiers in the question are pulled out with a pattern and looked up exactly against an indexed column, the question as a whole goes through semantic search for the closest 3, and the exact matches are listed first. The semantic half excludes anything the exact half already found, so no source is cited twice ([ADR-0017](decisions/0017-two-kinds-of-search-instead-of-one.md)).

The model is told to use the numbered sources only, to say plainly when they do not contain the answer, and for a failed payment to quote the real log lines and name the real rejection reason. `answer_service` is the only class that knows which provider is in use ([ADR-0018](decisions/0018-a-free-model-writes-the-answers.md)).

The two halves meet only at the `chunks` table, and both must use the same embedding model, numbers from one model mean nothing to another ([ADR-0016](decisions/0016-turn-text-into-numbers-on-this-machine.md)). An empty table answers `200` with "Nothing has been ingested yet"