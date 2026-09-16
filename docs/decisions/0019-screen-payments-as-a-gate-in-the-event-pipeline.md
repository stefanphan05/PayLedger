# ADR-0019: Screen payments as a gate in the pipeline, not a call from the API

**Date:** 16-09-2026

**Service:** payment-api-service, ledger-service, fraud-service

## Context

Payments needed checking for fraud before any money moved. The obvious shape is the front door asking a fraud service "is this alright?" and waiting for the answer.

Two things made that uncomfortable. Nothing else in this system works that way. `ledger-service` has no public API at all, and nothing waits on it. And the front door is the one place where being slow or unavailable is visible to a customer.

The other constraint is that counting matters. Most of the useful rules are about how many payments a sender has made recently, so whatever does the counting has to see every payment, in order, and not from a copy that lags behind.

## Decision

`fraud-service` sits between the existing payment topic and the ledger. It reads every payment, decides, and publishes the decision to its own topic. `ledger-service` now waits for a *cleared* payment instead of an *initiated* one.

`payment-api-service` is unchanged. It publishes a payment exactly as before and does not know the fraud service exists.

## Alternatives considered

#### Option A - The API calls the fraud service and waits

**How it works**: before saving a payment, the API makes an HTTP call and uses the answer

**Pros**:
- The customer is told immediately that a payment was refused, in the response to their own request
- The decision happens before anything is written down, so there is no state to unwind

**Cons**:
- Accepting a payment now depends on a second service being up *and* answering within a deadline. That is a new kind of fragility at the worst possible place
- Somebody has to decide what happens when it is slow or down. Allowing everything through defeats the feature; refusing everything stops the business. Both answers are bad and one of them has to be chosen
- Adds waiting time to every payment, including the overwhelming majority that are fine
- The counting problem stays: the fraud service still needs its own record of recent payments, and building it from a separate stream means it lags behind exactly when a burst is happening

#### Option B - Watch payments afterwards and raise an alert

**How it works**: read the payment stream and report suspicious ones after they settle

**Pros**:
- Cannot affect payments at all, so nothing to go wrong on the critical path
- Simplest thing that could be built

**Cons**:
- The money has already gone. For the pattern this is aimed at, an account being emptied quickly, an alert after the fact is a report, not a defence

#### Option C - Put the rules inside payment-api-service

**How it works**: a fraud package in the existing service, no new service at all

**Pros**:
- No new service, no new database, no new topic
- The decision is immediate and the customer learns the outcome in their response

**Cons**:
- Puts risk logic in the service whose job is accepting requests safely, and the two change for completely different reasons
- The counting has to read the payments table, which means fraud rules and payment records share a database and start constraining each other

## Consequences

**Gained**:
- The front door has no new dependency. If `fraud-service` is down, payments are still accepted and wait on the topic, then clear when it returns. There is no timeout to design and no "what if it is slow" policy to argue about
- Screening sees every payment in order, so the counts the rules read are exact rather than estimated
- A whole risk decision was added without touching how payments are accepted

**Gave up/new risk**:
- Settlement takes roughly twice as long, because there is one more store-and-forward hop. Seconds, not minutes, but it is a real regression and it will show up when settlement latency gets measured
- The customer no longer learns the outcome in the response. They are told `PENDING` and the refusal arrives a moment later, which is already how the ledger's verdict works, but it does mean a refused payment is never a `4xx`
- `ledger-service` had to change. Small, but the claim that new services cost nothing downstream is now weaker ([ADR-0020](0020-reuse-the-payment-envelope-so-the-ledger-barely-changes.md))
- A redelivered message is now dangerous in a new way: a second screening would publish a second cleared message with a different id, and the ledger checks ids, so it would move money twice. The duplicate guard in `fraud-service` is the only thing preventing that

**Revisit if**: a customer-facing product needs an immediate refusal at the point of payment. Even then, prefer a short wait for the verdict already on its way over a synchronous call, so the dependency stays soft.
