# ADR-0021: Score payments with weighted rules rather than a model

**Date:** 16-09-2026

**Service:** fraud-service

## Context

Fraud screening needed a way to turn "this payment looks wrong" into a decision. The obvious modern answer is a trained model that scores payments from past data.

Two things ruled against that here. There is no past data — this system has no real payment history to learn from, so anything trained would be trained on invented numbers. And a held payment has to be explainable to the person deciding whether to release it, and to the customer whose money is sitting still.

There was also a simpler question underneath: should a rule that fires make the decision on its own, or should several weak signals be allowed to add up?

## Decision

Five independent rules, each worth a fixed number of points. A payment's score is the sum of every rule that fired, capped at 100. Below 40 the payment clears, 40 to 69 holds it for review, 70 or more refuses it.

Every threshold and weight lives in configuration, not code.

## Alternatives considered

#### Option A - A trained model

**How it works**: learn from labelled past payments and score new ones

**Pros**:
- Finds patterns nobody thought to write down
- Adjusts as behaviour changes, without anyone editing thresholds

**Cons**:
- Needs labelled history this system does not have. Training on invented data produces confident nonsense
- "Why was my payment held?" has no good answer. Not a small problem when a person has to decide whether to release it
- Retuning means retraining, which is a different kind of work from changing a number

#### Option B - Any single rule decides

**How it works**: if a rule fires, the payment is held. No scoring

**Pros**:
- Simplest possible thing. Trivially explainable
- No weights to argue about

**Cons**:
- Every rule has to be safe enough to act alone, so each one ends up tuned conservatively and the weak signals get dropped entirely
- A large first payment to a new recipient would hold everyone buying a car. Making it not do that means deleting the rule, and then it cannot contribute at all
- No way to express "two mild signals together are worrying"

#### Option C - Rules with severity levels instead of points

**How it works**: each rule is low, medium or high; a high holds, two mediums hold

**Pros**:
- Easier to talk about than numbers
- Still explainable

**Cons**:
- The combining logic ends up as a table of special cases, which is arithmetic with extra steps
- Adding a rule means deciding how it interacts with each existing level, rather than picking one number

## Consequences

**Gained**:
- A held payment is always explainable by naming the rules that fired and their points
- Weak signals can contribute without being able to act alone. A large first payment to a new recipient is worth 30 — real, but not enough to hold anyone on its own
- Retuning is editing a configuration value, not a release
- Each rule is a small, independent function, so it can be tested on its own with no database and no framework

**Gave up/new risk**:
- The weights are guesses. Nothing measured them against real fraud, because there is no real fraud here to measure against. They are defensible, not correct
- Adding up unrelated signals has no statistical meaning. 40 plus 35 being 75 is a convention, not a probability
- Rules only see the payment and the sender's own history. Nothing about devices, addresses or locations, because the system never collects them
- Thresholds are absolute amounts with no currency conversion, so 10,000 means something different depending on what was sent

**Revisit if**: enough real outcomes accumulate to tell which rules actually catch fraud and which just annoy people. At that point the weights should be set from measurement rather than judgement — which is a smaller change than it sounds, because the shape stays the same and only the numbers move.
