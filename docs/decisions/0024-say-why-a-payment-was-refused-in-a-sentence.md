# ADR-0024: Say why a payment was refused in a sentence, not only a code

**Date:** 17-09-2026

**Service:** ledger-service, payment-api-service

## Context

The ledger already says why it refused a payment, and [ADR-0009](0009-store-the-rejection-reason-as-opaque-text.md) settled what the API does with that: store the text as it arrives, show it, never read it.

What the ledger sends is a code from a fixed list. A code is the same words every time, so it can describe the *kind* of problem but never the particular one.

An admin deposit showed what that costs. A user's account was opened by an AUD deposit. A later deposit of USD into the same account came back `CURRENCY_MISMATCH` and nothing else. Two things were wrong with that answer. It never said the account holds AUD, which is the one fact that tells the admin what to do next. And the wording belongs to a transfer, "sender and recipient must use the same currency", when a deposit has no sender at all.

Underneath is something the docs never stated out loud: an account holds one currency, decided the first time the ledger ever saw it, and never changed after. A wallet opened by an AUD payment can never take a USD one. The rule itself is fine. Leaving it unsaid is what made the refusal unreadable.

## Decision

Keep the code, and send a sentence alongside it.

The ledger writes the sentence where the refusal is decided, which is the only place that knows both currencies: "This account holds AUD, the deposit was in USD." A refusal with nothing extra to say falls back to a fixed sentence for its code, so every refusal has one.

The deposit and withdrawal case also gets its own code, `ACCOUNT_CURRENCY_MISMATCH`, so it stops being described as a disagreement between a sender and a recipient that a deposit does not have.

The API stores the sentence in `failure_detail` beside `failure_reason` and returns both. It still reads neither, exactly as ADR-0009 decided.

One currency per account stays as it is. This decision is only about explaining it.

## Alternatives considered

#### Option A - Put the values into the code

**How it works**: the code itself carries them, as `CURRENCY_MISMATCH_AUD_USD`

**Pros**: nothing new to carry; one field stays one field

**Cons**:
- The list of codes stops being a list. It becomes every pair of currencies, so refusals can no longer be counted or grouped, and the measurement tag that does exactly that would grow without limit
- Anything already matching on the code breaks the day a new currency appears
- The column holding it is 50 characters, a size chosen back when a code was only a code

#### Option B - Let the API write the sentence

**How it works**: the API keeps a phrase for each code and fills in the blanks

**Pros**: the event does not change, so only one service is touched

**Cons**:
- The API holds no accounts, so it does not know what currency the account holds. It could not write this particular sentence at all
- It would put the ledger's list of reasons back inside the API, which ADR-0009 turned down: a new reason would then mean changing and releasing two services together

#### Option C - Leave it, and read the logs

**How it works**: nothing changes; anyone who needs the currency goes and looks it up

**Pros**: no change at all

**Cons**:
- This is what already happened. Answering one admin's question meant reading a second service's logs and then querying its database. ADR-0009 turned the same idea down for the same reason
- The rejection log line did not name the currencies either, so even the logs could not answer it

## Consequences

**Gained**:
- A refused payment explains itself where the person is already looking, with no second service to go and ask
- The code stays a short fixed list, so it is still safe to count, group and branch on
- The rejection log line now names both currencies, so `insights-service`, which reads the logs ([ADR-0022](0022-let-the-logs-carry-fraud-decisions-to-insights.md)), can answer "why did this fail" instead of repeating the code back
- The sentence carried on each reason, which nothing had ever read, is now what a refusal falls back to

**Gave up/new risk**:
- Two fields have to move together. The ledger's verdict sets both, a status changed by hand clears both. Setting one without the other would leave a sentence explaining a failure that is no longer there
- The sentence is written by the ledger, in English, for a person to read. It is not a value to branch on, and showing it in another language later means sending the pieces instead of the prose
- Payments that failed before this existed keep an empty sentence. They are not backfilled, so the API returns the code alone for them
- While the services are being released the old ledger is still publishing events with no sentence in them. The field is optional for that reason, and a settlement without one still completes

**Revisit if**: an account is ever allowed to hold more than one currency, which removes most of what this sentence exists to explain; or the message has to appear in more than one language, at which point the ledger should send the parts and the reader should assemble them.
