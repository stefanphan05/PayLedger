# ADR-0017: Two kinds of search instead of one

**Date:** 09-09-2026

**Service:** insights-service

## Context

Two questions arrive at the same endpoint:

> why did transaction demo-8 fail?

> why optimistic locking instead of pessimistic?

They look like the same kind of request. They are not, and building them as though they were is the single easiest way to get this feature wrong.

The second question is a search by meaning. Nothing in it has to match a document word for word — the answer lives in [ADR-0001](0001-optimistic-locking-for-transaction-updates.md), which may never use the word "pessimistic" in the sentence that actually answers it. Comparing meanings is exactly what turning text into numbers is for.

The first question is not a search at all. `demo-8` is a name. It does not mean anything. Turning it into numbers produces a list with no more significance than any other, and comparing it to the list for `demo-7` produces a number that reflects how the two strings happen to be chopped up, not whether they are related. There is no meaning in a name for the comparison to find.

The failure this causes is the dangerous kind. Ask about a specific payment and a meaning-only search still returns something — some other payment's log lines, which look right, read fluently, and describe a different payment entirely. Nothing errors. Nothing looks wrong.

## Decision

The service runs both kinds of search and combines the results.

Anything in the question that looks like an identifier is pulled out and looked up exactly, against the indexed columns holding the correlation id and the transaction id. The question as a whole is separately compared by meaning against everything else. Exact matches come first, and the meaning-based search skips anything already found that way.

A question naming a payment gets that payment's log lines, guaranteed, plus whatever design context is relevant. A question about a design choice gets the design context alone, because no identifier was found to look up.

## Alternatives considered

#### Option A - One search by meaning, covering identifiers too

**How it works**: store the ids as part of the text, turn everything into numbers, and let the comparison find whatever it finds

**Pros**:
- One code path, roughly half the code, and nothing to explain
- Works acceptably for every question that is not about a specific payment

**Cons**:
- Fails at the exact thing this feature exists to do. Incident triage is the stronger half of the use case, and it is the half this breaks
- It fails silently and convincingly. A wrong answer about the wrong payment is worse than an error, because nothing prompts anyone to check
- No amount of tuning fixes it, because there is no signal being missed — a name genuinely does not carry meaning to compare

#### Option B - Two endpoints, one for lookups and one for questions

**How it works**: the caller picks. One endpoint takes an id, the other takes a question

**Pros**:
- Each is simple and does one thing, with no guessing anywhere
- No chance of misclassifying a question, because nothing is classified

**Cons**:
- Moves the decision onto whoever is asking, who often cannot make it. "Why do payments to that account keep failing?" needs both, and the caller has no way to know that
- The two-endpoint split is an implementation detail leaking into the interface. Someone asking a question in plain English should not have to know how the answer gets found

#### Option C - Let the language model decide which search to run

**How it works**: describe both searches to the model and let it choose, and with what input

**Pros**:
- Handles awkward middle cases more gracefully than a pattern ever will
- Extends naturally if a third kind of search is added later

**Cons**:
- An extra call to the model, and the waiting that comes with it, before any searching starts
- Introduces a new way to fail — the model picking wrong — in place of something a simple pattern settles reliably
- Costs money on every question, including the ones where the answer was never in doubt

## Consequences

**Gained**:
- Questions about a specific payment are answered from that payment's actual log lines, every time, by an indexed lookup that costs almost nothing
- The two halves stay independent. Improving how documents are split has no effect on payment lookups, and vice versa
- Each result records which of the two searches found it. That is deliberate: if identifier matching ever silently stops working, it shows up as results that are all meaning-based, rather than as answers that are quietly about the wrong payment

**Gave up/new risk**:
- Something has to decide what looks like an identifier. The rule is that it contains a digit — which is true of `demo-8` and of every transaction id, and not true of `transaction` or `happened`. An identifier made only of letters would be missed
- The first version of that rule required eight characters or more, and silently missed every `demo-5` style id in the existing logs. Nothing failed; the meaning-based search just quietly answered instead. It was only caught by testing the search directly rather than reading the answers, which is worth remembering as the general lesson here
- Candidate identifiers are looked up whether or not they are real, so a question containing a word with a digit in it costs one extra indexed query that finds nothing. Cheap, and preferable to being clever about it

**Revisit if**: identifiers ever stop containing digits, or a third kind of question appears that is neither a lookup nor a search by meaning. At that point Option C becomes worth its cost, because the choice stops being one a pattern can make.
