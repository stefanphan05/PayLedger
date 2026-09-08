# ADR-0009: Store the ledger's rejection reason as plain text

**Date:** 08-09-2026

**Service:** payment-api-service

## Context

When the ledger refuses a payment it says why, for example "not enough money". The API now saves that reason so the user sees something better than "failed".

The ledger owns that list of reasons and will add to it over time. So the question is: should the API keep its own copy of the list, or just store whatever text it is sent?

## Decision

Store the text exactly as it arrives, in a `failure_reason` column. The API never inspects it, it saves it and shows it.

## Alternatives considered

#### Option A - Keep a matching list of reasons in the API

**How it works**: copy the ledger's list of reasons into this service

**Pros**: the API can only ever store a reason it recognises

**Cons**:
- The day the ledger adds a new reason, the API cannot read the message at all. The payment is then stuck unfinished, the worst outcome, caused by a message we only needed to pass along
- Adding one reason becomes a change to two services instead of one
- The API never makes decisions based on the reason, so the extra safety buys nothing

#### Option B - Put the list in a shared library both services use

**How it works**: one copy of the list, shared as a dependency

**Pros**: no risk of the two lists drifting apart

**Cons**:
- Ties the two services back together, which is the coupling [ADR-0003](0003-kafka-for-internal-service-messaging.md) set out to remove. What the services agree on is the message format, not shared code
- Both services then have to be updated and released together

#### Option C - Save nothing, keep the reason in the ledger's logs

**How it works**: the API records only "failed"

**Pros**: no new column, smallest change

**Cons**:
- The user is told their payment failed and nothing more
- Answering "why" means an engineer digging through another service's logs
- The ledger already sends the reason, so this throws away information we have

## Consequences

**Gained**:
- The ledger can add new reasons without touching or redeploying the API
- An unfamiliar reason still finishes the payment correctly and still reaches the user
- The two services stay independent

**Gave up/new risk**:
- Nothing checks that a stored reason is one the ledger really sends. A typo would be saved and displayed as-is
- The text goes straight into the API response, so `docs/api.md` describes it as a message to show the user, not a value to build logic on. Anything that does build logic on it is relying on a promise this decision does not make

**Revisit if**: the API ever needs to behave differently depending on the reason, a different error code, or a translated message. Even then, map the text to something known and fall back safely on anything unrecognised, so a new reason still cannot break the payment.
