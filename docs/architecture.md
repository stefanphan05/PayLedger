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
    participant Kafka as Apache Kafka
    participant Fraud as fraud-service
    participant FraudDB as Fraud DB
    participant Ledger as ledger-service
    participant LedgerDB as Ledger DB

    Client->>PayAPI: POST /transactions<br/>Idempotency-Key, X-Correlation-Id (optional)
    PayAPI->>Redis: SETNX user + key + body hash
    Redis-->>PayAPI: claimed (a repeat is replayed instead)
    PayAPI->>PayDB: INSERT transaction (PENDING)<br/>+ outbox_events (correlation_id)
    PayAPI-->>Client: 201 PENDING
    Note over PayAPI: 1. Unchanged by fraud screening. This<br/>service does not know fraud-service exists

    PayDB-->>Kafka: Outbox relay (1s):<br/>payment-events PAYMENT_INITIATED

    Kafka->>Fraud: Consume payment-events
    Fraud->>FraudDB: SELECT this sender's recent history
    Note over Fraud: 2. One query feeds all five rules.<br/>This sits on the settlement path
    Fraud->>FraudDB: INSERT processed_events + payment_attempts<br/>+ outbox_events
    Note over FraudDB: 3. One transaction. The duplicate guard, the<br/>decision and the message commit together

    FraudDB-->>Kafka: Outbox relay (1s): fraud-events

    alt score below 40 - ALLOW
        Kafka->>Ledger: Consume fraud-events: PAYMENT_CLEARED
        Note over Ledger: 4. The only message it acts on. Its DTOs<br/>never changed: same envelope, new topic
        Ledger->>LedgerDB: INSERT processed_events + 2 entries<br/>+ UPDATE balances + INSERT outbox
        LedgerDB-->>Kafka: Outbox relay: ledger-events<br/>PAYMENT_COMPLETED or PAYMENT_FAILED
        Kafka->>PayAPI: Consume ledger-events
        PayAPI->>PayDB: UPDATE status COMPLETED or FAILED
    else score 40 to 69 - REVIEW
        Kafka->>PayAPI: Consume fraud-events: PAYMENT_HELD
        PayAPI->>PayDB: UPDATE status UNDER_REVIEW
        Note over Ledger: 5. Never receives it. No money moves<br/>until a person releases it
    else score 70 or more - BLOCK
        Kafka->>PayAPI: Consume fraud-events: PAYMENT_BLOCKED
        PayAPI->>PayDB: UPDATE status FAILED,<br/>failure_reason + failure_detail
    end
```

Two of the three outcomes never reach the ledger, which is the whole point of screening before settlement rather than after it.

Screening adds one store-and-forward hop, so a payment now takes roughly twice as long to settle as it used to: two outbox relays at a second each rather than one. A cleared payment still settles in a few seconds. A held one waits for a person, with no timeout.

### Monitoring and insights

```mermaid
sequenceDiagram
    autonumber
    participant Prom as Prometheus
    participant PayAPI as payment-api-service
    participant Ledger as ledger-service
    participant Fraud as fraud-service
    actor Operator
    participant Corpus as The corpus
    participant Insights as insights-service
    participant Gemini as Google Gemini

    loop every 15s
        Prom->>PayAPI: GET /actuator/prometheus
        Prom->>Ledger: GET /actuator/prometheus
        Prom->>Fraud: GET /actuator/prometheus
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

It works out which two accounts a payment touches from its type. A transfer moves
between two wallets. A deposit and a withdrawal have the platform's own cash account
on one side, which the ledger finds by currency — the sender who asked never names
it ([ADR-0023](decisions/0023-one-transactions-table-for-money-in-and-out.md)).

Six things reject a payment ([ADR-0009](decisions/0009-store-the-rejection-reason-as-opaque-text.md)):

- `CURRENCY_MISMATCH` — a transfer whose two wallets do not agree
- `ACCOUNT_CURRENCY_MISMATCH` — a deposit or withdrawal in a currency that account does not hold
- `SELF_TRANSFER`
- `INSUFFICIENT_FUNDS`
- `NO_FUNDING_ACCOUNT` — a deposit or withdrawal in a currency the platform holds no cash in
- `FUNDING_ACCOUNT_SHORT` — the float cannot cover a withdrawal, which should never happen

Each one carries a sentence as well as a code, because a code alone cannot say which
currency an account holds ([ADR-0024](decisions/0024-say-why-a-payment-was-refused-in-a-sentence.md)).

All of them still publish an event, a refusal is an answer, and the `payment-api-service` is holding a `PENDING` row waiting for one.

### 3. fraud-service

No public API for payments. It reads `payment-events`, decides, and publishes to `fraud-events`. Nothing calls it and nothing waits on it, so if it is down payments are still accepted and simply wait on the topic.

Five rules score a payment out of 100: how fast the sender is paying, how large the amount is, how it compares to their own average, whether the recipient is new, and how many different people they have paid. Below 40 the payment clears, 40 to 69 holds it for a person, 70 or more refuses it ([ADR-0021](decisions/0021-a-weighted-rule-score-instead-of-a-model.md)).

Deposits are the exception: they are cleared on sight, without the rules running and without a decision being recorded. A deposit has no sender, and every rule asks what one sender has been doing recently, so there is nobody to ask about. There is also nothing to catch — these rules look for an account being emptied, and a deposit is the opposite.

The duplicate guard matters more here than anywhere else. A redelivered message would produce a *second* cleared message with a new id, and the ledger, which checks ids, would not recognise it as a repeat and would move the money twice ([ADR-0019](decisions/0019-screen-payments-as-a-gate-in-the-event-pipeline.md)).

It has its own admin API on port 8082 for the review queue, and works out whether a caller is an admin by reading the role out of the token rather than looking anyone up.

### 4. insights-service

Read-only. It searches first, and shows the model only what won.

It never searches one way. Identifiers in the question are pulled out with a pattern and looked up exactly against an indexed column, the question as a whole goes through semantic search for the closest 3, and the exact matches are listed first. The semantic half excludes anything the exact half already found, so no source is cited twice ([ADR-0017](decisions/0017-two-kinds-of-search-instead-of-one.md)).

The model is told to use the numbered sources only, to say plainly when they do not contain the answer, and for a failed payment to quote the real log lines and name the real rejection reason. `answer_service` is the only class that knows which provider is in use ([ADR-0018](decisions/0018-a-free-model-writes-the-answers.md)).

The two halves meet only at the `chunks` table, and both must use the same embedding model, numbers from one model mean nothing to another ([ADR-0016](decisions/0016-turn-text-into-numbers-on-this-machine.md)). An empty table answers `200` with "Nothing has been ingested yet"