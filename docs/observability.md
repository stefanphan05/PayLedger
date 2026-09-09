# Observability

How to find out what happened to one payment, and how to tell when the system is quietly falling behind.

This is the companion to [architecture.md](architecture.md): that diagram shows what moves, this one shows what you can see while it moves. The reasoning behind each choice is in [ADR-0011](decisions/0011-carry-the-correlation-id-in-a-kafka-header.md), [ADR-0012](decisions/0012-log-as-json-in-containers-only.md) and [ADR-0013](decisions/0013-watch-the-outbox-backlog-age.md).

## Why the id has to be stored, not remembered

The outbox row is written on the thread handling the HTTP request. The message is sent up to a second later by the poller's scheduled thread, which has never seen that request and cannot see anything the request left in memory. Four different threads across two operating system processes take part in one payment, and only the first of them is the request.

So an in-memory trace context does not survive. Automatic framework tracing would tag the poller's own work rather than the caller's, producing fragments that cannot be joined up. The id has to be written to the database next to the message and read back when the message is sent. That is the cost, and it buys the rest of this document.

## What every log line carries

Two ids, answering two different questions.

| Field | Question it answers | Where it is set |
|---|---|---|
| `correlationId` | What happened during this one flow? | `CorrelationIdFilter` at the HTTP edge; re-established from the record header in both Kafka listeners |
| `transactionId` | Everything that ever happened to this payment | `TransactionService` on create and settle; both listeners once the message is parsed |

Both are put on the logging context rather than passed into individual log calls, so every line in scope carries them without the call site knowing. In containers the lines are written as JSON and the two ids appear as fields; on your machine the console stays plain and readable.

`correlationId` accepts a client-supplied value on the request. It is checked first, letters, digits, dash and underscore, 64 characters at most — because it arrives from outside and ends up both in the log stream and in a database column. Anything else is replaced with a freshly generated id rather than rejected.

## Debugging an incident

**Find a whole flow, both services, in order.**

```bash
docker compose logs --no-log-prefix | grep <correlation-id> | jq -r '
  [.["@timestamp"][11:23], .log.level, .service.name, (.log.logger|split(".")|last), .message] | @tsv' \
  | column -t -s $'\t'
```

`--no-log-prefix` is required. Without it Docker prepends `ledger-1  | ` to each line and it stops being valid JSON, so `jq` fails on every row. Swap the correlation id for a transaction id and the same command answers "everything that ever happened to this payment".

A healthy round trip looks like this — four lines, four threads, two services:

```
13:10:46.646  INFO  payment-api-service  TransactionService     Accepted 200 AUD from 4cb55b9f… to c98b2a70…
13:10:47.893  INFO  ledger-service       PaymentEventListener   Applied transaction c4121bdb…
13:10:48.237  INFO  ledger-service       OutboxPublisher        Published PAYMENT_COMPLETED → ledger-events-0@1
13:10:48.260  INFO  payment-api-service  TransactionService     Settled as COMPLETED
```

**Is the outbox falling behind?** At `localhost:9090`:

```promql
payledger_outbox_oldest_age_seconds
```

Zero is healthy. A value that climbs by one per second means the relay is not getting messages out — the queue is unreachable, or the poller has stopped. This is the single most useful number in the system, because it is the only symptom of the failure that is otherwise silent.

```promql
payledger_outbox_unpublished                        # how many are waiting
payledger_ledger_outcome_total                      # what the ledger is deciding, by reason
sum by (application) (payledger_outbox_unpublished) # both services in one answer
```

The `application` label is what makes that last one work, and it comes from a single line of configuration rather than from each metric naming itself.

## Timing, and what it tells you

A measured round trip: **1.614 seconds** end to end.

```
Accepted   →  Applied     1.247 s
Applied    →  Published   0.344 s
Published  →  Settled     0.023 s
```

Almost all of it is the first gap, and that is the outbox poller's one-second interval, the payment sits committed in the database waiting to be picked up. That delay is the visible price of never losing an event, and it is a tuning knob (`payment-events.poll-interval`), not a fault. Shortening it costs one database query per interval per service.

## What is deliberately not here

- **No log aggregator.** Two containers and `grep` is enough at this size. A shipper and a search backend is a lot of infrastructure to answer a question `docker compose logs` already answers.
- **No alerting rules.** The measurements exist and the failure is visible, but nothing raises an alarm, someone has to look. The obvious first rule is `payledger_outbox_oldest_age_seconds` staying above a minute.
- **No distributed tracing.** No spans, no timing breakdown within a service. The correlation id answers "what happened", not "where did the time go". [ADR-0011](decisions/0011-carry-the-correlation-id-in-a-kafka-header.md) covers when that would be worth adding.
- **Retry and drop logs carry no id.** When a message cannot be read at all, the retries and the eventual giving-up are logged by the messaging framework, outside any code of ours, so they are the one part of a payment's history with nothing to search for. Feature 08's dead letter topic is where that gets fixed.
- **The measurement endpoints have no login.** Fine while they are only reachable on the private network between containers. Exposing them publicly would need that revisited.
