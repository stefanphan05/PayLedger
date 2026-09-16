# Testing

What is tested, how to run it, and which parts are deliberately left uncovered.

Both Kotlin services use JUnit 5. Anything that touches a database or a queue runs against a real one in a container rather than a fake, so the tests fail for the same reasons production would.

## Running them

```bash
cd payment-api-service && ./gradlew test     # or cd ledger-service, or fraud-service
```

Docker has to be running, most of these tests start a Postgres or a Kafka. A coverage report is written automatically after every run:

```
build/reports/jacoco/test/html/index.html
```

Latency benchmarks are excluded from the normal run, because they measure timing rather than correctness and are slow. Opt in:

```bash
./gradlew benchmark
```

## What each test is for

**payment-api-service**

| Test | What it proves |
|---|---|
| `TransactionServiceTests` | validation and status rules, without a database |
| `PostgresIntegrationTests` | the schema, the constraints, and optimistic locking really behave |
| `IdempotencyServiceTests` | the state machine: claim, replay, deterministic failure, ambiguous hold |
| `IdempotencyIntegrationTests` | the same against a real Redis |
| `ConcurrentRetryTests` | two identical requests at once produce one payment |
| `ClientErrorRegistryTests` | a stored rejection can be rebuilt and replayed as the same error |
| `OutboxAtomicityTests` | the payment and its outbox row commit together or not at all |
| `OutboxIntegrationTests` | the poller picks rows up, sends them, and marks them sent |
| `OutboxPublisherTests`, `PaymentEventPublisherTests` | the message shape and topic |
| `SettlementIntegrationTests` | a ledger verdict flips `PENDING` to `COMPLETED` or `FAILED` |
| `LedgerVerdictContractTests` | this service can still read what the ledger sends |
| `JwtUtilityTests` | signing, expiry, and rejecting a tampered token |
| `LogContextTests` | the correlation id reaches the log line |
| `IdempotencyOverheadBenchmark` | what idempotency costs per request (tagged `benchmark`) |

**ledger-service**

| Test | What it proves |
|---|---|
| `LedgerServiceIntegrationTests` | funds checks, double-entry, balances, and refusal reasons |
| `AccountTests` | how a debit or credit moves an `ASSET` versus a `LIABILITY` |
| `LedgerVerdictContractTests` | this service still sends what the API expects |
| `LogContextTests` | the correlation id survives the Kafka hop |

## The fraud suite

`fraud-service` splits cleanly in two.

The **five rules are pure functions** of a payment and the sender's history, so they test with no Spring context, no database and no mocks. Each one is pinned at its threshold: exactly at the limit must stay quiet, one over must fire. That boundary is the difference between catching a burst and holding someone's rent, and two real bugs were caught by writing it down — two rules had their bodies swapped, and one compared nothing at all and fired on every payment.

Everything else runs against a real Postgres. The one that matters most is the redelivery test: screening the same message twice must produce exactly **one** outgoing message. It is the only thing standing between an at-least-once delivery and the ledger moving the same money twice, because a second screening would publish a verdict with a new id that the ledger cannot recognise as a repeat.

There is also a test asserting the decision log line contains the score and the rule names. That line is the only route a decision takes into the searchable corpus, so if it stops carrying numbers, every future answer about a blocked payment quietly becomes useless.

## The contract pair

The two services never run together in one test. They have files with the same names in the same packages, so they cannot start inside a single JVM, and running them as two separate programs cost far more setup than it proved
([ADR-0010](decisions/0010-contract-pair-instead-of-end-to-end-tests.md)).

Instead there is one example message, saved as the same file in both services:

```
src/test/resources/contract/ledger-verdict.json
```

- The **ledger** runs a real payment and checks the message it produced still matches the file.
- The **API** takes that file, sends it through a real Kafka, and checks the payment settles.

Change the message format on either side and one of the two tests fails. The catch is that the file is duplicated: editing one copy and not the other makes both tests pass while the services disagree.

### And a contract set of three

Fraud screening added a second message, and this one has two readers rather than one:

```
src/test/resources/contract/fraud-verdict.json
```

- **fraud-service** screens a real payment and checks the message it produced matches the file, field for field.
- **ledger-service** reads the file with the data classes it already had, and checks every field it needs to move money survived.
- **payment-api-service** reads the same file and checks it can pick out the verdict.

The ledger's half is the important one. Its data classes ignore fields they do not recognise, so a renamed field would not fail a build — it would read as absent, and settlement would break quietly. This test is the only thing that turns that into a loud failure.

## Coverage

Measured with JaCoCo, written after every `./gradlew test`.

| Service             | Instructions | Branches |
| ------------------- | ------------ | -------- |
| payment-api-service | 71%          | 68%      |
| ledger-service      | 62%          | 70%      |

![](../assets/test-coverage-payment-api.png)

![](../assets/test-coverage-ledger.png)

The totals matter less than their shape. The code that decides what happens to money is the well covered part:

| Package | Covered |
|---|---|
| `ledger.service` (ledger) | 97% |
| `ledger.consumer` (API) | 94% |
| `idempotency.service` | 93% |
| `transaction.service` | 92% |
| `shared.observability` | 96% / 100% |

What drags the totals down is wiring rather than logic: controllers, config classes, DTOs and exception types, where a test would mostly assert that a constructor assigns its arguments.

## What is deliberately not tested

- **No end-to-end test.** Nothing starts both services and drives them like a user would. The contract pair is the substitute, and it does not prove the two run correctly together only that they agree about the message.
- **No load test in the suite.** `payment-api-service/load-test/` is a separate k6 run, started by hand.
- **Deposits and withdrawals are not covered yet.** The three suites pass with the new routes in place, and the contract fixtures were updated so the pair still agree, but nothing yet exercises a deposit raising both sides of the ledger, a withdrawal lowering them, an overdrawn withdrawal, or a currency with no funding account. Those are the first tests to write.
