# ADR-0020: Republish the same message shape so the ledger barely changes

**Date:** 16-09-2026

**Service:** fraud-service, ledger-service

## Context

Putting fraud screening between the payment topic and the ledger ([ADR-0019](0019-screen-payments-as-a-gate-in-the-event-pipeline.md)) means the ledger stops reading the payment topic and starts reading the fraud one.

The ledger needs the payment itself to do its job: who is paying, who is being paid, how much, in what currency. The fraud service needs to say what it decided and why. Those are different things and they have to travel together somehow.

The ledger is the part of this system that moves money. Any change to it is a change worth being nervous about, so the question was how to add a stage in front of it while touching it as little as possible.

## Decision

`fraud-service` republishes the payment in **exactly the shape it arrived in**, with the same field names, and adds the decision alongside it as an extra field.

The ledger's change is a topic name and one constant. Its data classes are untouched, because they already ignore fields they do not recognise and already read the message type as plain text.

## Alternatives considered

#### Option A - A new, purpose-built message for the ledger

**How it works**: design a "cleared payment" message containing only what the ledger needs

**Pros**:
- Cleaner in the abstract: each message says one thing, with no passenger data
- The decision does not travel to a service that does not care about it

**Cons**:
- The ledger's data classes change, which means the part that moves money changes, for no behavioural reason
- Two message shapes now describe the same payment, and they can drift apart
- The existing test that pins the message format has to be rewritten rather than extended

#### Option B - Send only an id and let the ledger fetch the payment

**How it works**: the cleared message carries a transaction id, and the ledger calls back for the details

**Pros**:
- Smallest possible message
- No duplicated payment data anywhere

**Cons**:
- Puts a call from the ledger back into the payments service, which is exactly the coupling [ADR-0003](0003-kafka-for-internal-service-messaging.md) removed
- The ledger cannot work through a backlog without the other service being up

#### Option C - Two messages: one for the ledger, one for the API

**How it works**: publish a plain cleared message and a separate decision message

**Pros**:
- Each consumer gets only what it wants

**Cons**:
- Two messages describing one decision, which can arrive in either order or partially fail
- Both consumers now have to know about a message they ignore anyway

## Consequences

**Gained**:
- The service that moves money changed by two lines. Its logic, data classes, repositories and existing contract test are untouched
- The decision travels with the payment, so the payments API can act on it without a second message
- A consumer that does not care about the decision simply does not see it

**Gave up/new risk**:
- The message is bigger than either consumer needs. Each one carries payment details *and* a decision, and every reader gets both
- The field names in the payload are now load-bearing in a way that is easy to miss. Renaming one would not fail a build — the ledger ignores fields it does not recognise, so it would read the renamed field as absent and settlement would break quietly. The shared contract fixture across all three services exists to catch exactly that, and it is the only thing that does
- Two services now define their own copy of the same payload shape, kept in step by that fixture rather than by the compiler

**Revisit if**: the decision grows large enough to be worth splitting out, or a third consumer appears that needs one half and not the other.
