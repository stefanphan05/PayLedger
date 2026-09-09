# ADR-0011: Give every payment one id you can search both services for

**Date:** 09-09-2026

**Service:** payment-api-service, ledger-service

## Context

One payment touches both services. It is accepted by the first, decided by the second, and the answer travels back to the first. Until now, finding out what happened to a particular payment meant reading two separate sets of logs by hand and hoping the timestamps lined up.

The fix is one id, attached to a payment when it arrives and carried everywhere it goes. The difficulty is where to keep that id in transit.

The two services do not talk directly. The first one writes the message into its own database and a background job sends it a moment later ([ADR-0004](0004-outbox-pattern-for-kafka-events.md)). Those are two different pieces of work happening at different times: the request is long finished by the time the message goes out. Anything held only in memory while the request is being handled is gone before the sending happens.

So the id cannot simply be remembered. It has to be written down alongside the message and picked up again later.

## Decision

The id is stored in a new column next to each waiting message, and copied onto the message as a label when it is sent. The service on the other side reads the label off the message and uses it for everything it logs.

The same thing happens on the way back, so a single search finds all of it: the payment being accepted, the ledger deciding, and the answer being applied.

If a message arrives with no label — anything sent before this existed — the payment's own id is used instead, so there is always something to search for.

## Alternatives considered

#### Option A - Put the id inside the message itself

**How it works**: add a field to the message, next to the payment details it already carries

**Pros**:
- No database change. The message is already stored as text, so a new field costs nothing
- Both services already ignore fields they do not recognise, so neither would break

**Cons**:
- Mixes up two different things: what a message *means* and how we *follow* it around. The id is not part of the payment, it is bookkeeping about the delivery
- Breaks the shared example message from [ADR-0010](0010-contract-pair-instead-of-end-to-end-tests.md). That example is copied into both services and both check against it, so adding a field means editing it twice and updating both tests — a change about searching logs would show up as a change to the message format

#### Option B - Let the framework follow requests around on its own

**How it works**: switch on the built-in tracing that most frameworks offer, and let it label outgoing messages automatically

**Pros**:
- Almost no code to write
- Produces the standard labels other tools already understand

**Cons**:
- Does not work here, and fails quietly rather than loudly. It labels whatever piece of work is running at the moment the message is sent — and that is the background sending job, not the original request. Every payment would come out split into unrelated fragments that cannot be joined back together
- Would have looked correct in testing, because the pieces are individually well formed

## Consequences

**Gained**:
- One search now returns a whole payment across both services, in order — accepted, decided, answered
- The shared example message did not change, so both format tests still pass untouched. That was the point of choosing a label over a field
- The id survives the return journey as well, so the ledger's answer is findable under the same id the request started with
- Every payment also carries its own id in the logs, so you can search either by the request or by the payment

**Gave up/new risk**:
- An extra column, and an extra label on every message
- The id arrives from outside, so it has to be checked before it is stored: only plain characters, and no longer than the column allows. A strange one is replaced rather than trusted
- Where no id is available, the payment's own id is used instead. That is a sensible stand-in, but it happens silently — nothing announces that the real id was missing
- The two services each keep their own copy of the code that reads and checks the id. Fixing a fault in one means remembering the other

**Revisit if**: proper end-to-end tracing is adopted later. The label becomes the standard one every tool recognises, and this hand-built version goes away. Worth looking at again when the dead letter queue arrives in feature 08 as well — when a message cannot be read at all, the retries and the eventual giving-up are reported by the framework, outside our code, and those are currently the only lines in a payment's history with no id attached.
