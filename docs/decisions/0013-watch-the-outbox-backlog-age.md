# ADR-0013: Measure how long the oldest unsent message has been waiting

**Date:** 09-09-2026

**Service:** payment-api-service, ledger-service

## Context

The worst failure this system has is a quiet one.

Messages are written to the database first and sent a moment later by a background job ([ADR-0004](0004-outbox-pattern-for-kafka-events.md)). That is deliberate — it is what stops a payment being accepted without the ledger ever hearing about it. But it also means that if the message queue disappears, nothing looks wrong from the outside. Payments are still accepted. Every request still succeeds. The waiting pile just grows, and nobody finds out until someone checks a balance and it is wrong.

Nothing in the system reported on itself, so there was no way to see this happening.

## Decision

Both services publish a set of measurements at an address that a separate collector reads on a timer.

Most of them come free with the framework: how many requests, how long they took, how much memory is in use. Two were written by hand, and they are the ones that matter here:

- how many messages are still waiting to be sent
- **how long the oldest one has been waiting**

The second is the one to watch. It goes up by one every second the queue is unreachable and drops back to zero as soon as the pile clears.

There is also a tally of what the ledger decided — accepted, or refused and why.

## Alternatives considered

#### Option A - Watch how many messages are waiting, rather than how long

**How it works**: raise an alarm when the number of unsent messages goes above some figure

**Pros**:
- Easier to explain, and the more obvious thing to reach for

**Cons**:
- Wrong, and wrong in a way that produces both false alarms and missed failures. A large pile during a busy spell is completely normal and clears itself in seconds. A single message stuck for ten minutes is an outage. Counting cannot tell those two apart
- Any threshold you pick has to be re-guessed every time traffic changes. Waiting time needs no such guess: a message that has been sitting for minutes is always wrong, at any volume

#### Option B - Have each service send its measurements out

**How it works**: each service pushes its numbers to a central collector on a timer

**Pros**:
- Works even when the collector cannot reach back into the service

**Cons**:
- A service that has died stops sending, which looks exactly the same as a healthy service with nothing to report. The most important failure becomes invisible
- Every service has to be told where to send things, so the address is duplicated across the system instead of living in one place

## Consequences

**Gained**:
- The queue failure is now visible before anyone is affected. Confirmed by stopping the queue, making a payment, and watching the waiting time climb — then start the queue and watch it fall straight back to zero
- Request counts, response times and memory came free with the endpoint. None of that had to be written
- Both services report the same measurements and each labels itself, so one question can be asked of the whole system at once
- The tally of ledger decisions gives a running picture of how often payments are refused, and for which reason

**Gave up/new risk**:
- Each collection runs two small database queries per service. They are cheap and use an index that already existed, but they are not free, and they run whether or not anything is happening
- The decision tally is counted after the ledger's work is safely stored, not during it. This is deliberate: a redelivered message that gets undone must not leave a phantom count behind
- Labels are kept to a short fixed list on purpose. A label carrying something unbounded, such as a payment id, would create a separate series for every payment and overwhelm the collector. This is an easy mistake to make later
- The measurement address is currently reachable without a login. Acceptable while it is only exposed inside the private network between the containers, and not to the outside world

**Revisit if**: more than one copy of a service is ever run. The collector is currently given a fixed list of addresses, which stops working the moment those addresses are not known in advance. Also revisit when alerting is actually wanted — the measurements exist now, but nothing raises an alarm; today it only shows up if somebody looks.
