# ADR-0018: A free model writes the answers, and citations are weaker for it

**Date:** 09-09-2026

**Service:** insights-service

## Context

Once the relevant log lines and document sections have been found, something has to read them and write an answer in plain English. That is a language model, and every serious one is a paid service.

This project has no budget, runs on one laptop, and is built to be demonstrated rather than operated. That points at a free option, provided the trade is understood rather than stumbled into.

There is a second requirement that turns out to matter more than cost. An answer about a failed payment is only useful if it can be checked. The service has to say which log lines and which document sections it used, or it is producing plausible text rather than evidence. How well a given provider supports that turns out to be the real difference between the options.

## Decision

Answers are written by a free model from Google, called through its standard interface, using the free allowance available to any account.

The retrieved pieces of text are numbered before being handed over, and the model is asked to mark each claim with the number it came from. Those markers are read back out of the answer and turned into the list of sources the endpoint returns.

## Alternatives considered

#### Option A - The paid provider that supports citations properly

**How it works**: hand each retrieved piece of text over as a separate labelled source, with citation support switched on

**Pros**:
- Genuinely better at the one thing that matters here. The provider returns the exact passage that each part of the answer came from, worked out on its side rather than claimed by the model. The model cannot invent one
- That makes an answer verifiable rather than merely sourced, which is the difference between "here is what happened" and "here is something that sounds like what happened"
- No numbering to add, no markers to parse, and no way for the two to drift apart

**Cons**:
- Costs money on every question, in a project with no budget. This is the whole reason it was not chosen, and it is worth being plain about that rather than inventing a technical objection
- Another account and another key

#### Option B - The other large paid provider

**How it works**: same shape as the chosen option, through a different company

**Pros**:
- Strong at following the instruction to answer only from what it is given, which is the instruction this design leans on hardest

**Cons**:
- Paid, with no free allowance worth building on
- Offers nothing better than Option A on citations, so it takes the cost without the advantage that made Option A tempting

#### Option C - Run a model on this machine

**How it works**: the same approach used for turning text into numbers, extended to writing the answers

**Pros**:
- Free, private, and works with no internet at all
- Nothing about this project's logs would leave the machine at any point

**Cons**:
- The models small enough to run on a laptop are noticeably worse at staying inside the sources they are given. They fill gaps with general knowledge about payment systems, which is exactly the failure this whole feature is meant to avoid
- Several more gigabytes on top of an image that is already large
- Slow enough per answer to make the endpoint feel broken

## Consequences

**Gained**:
- No cost, so the service can be left running and demonstrated freely
- The model is fast and easily good enough for the job, which is summarising a handful of sources rather than reasoning hard
- Only one company is involved in the running system, since the numbers are produced locally ([ADR-0016](0016-turn-text-into-numbers-on-this-machine.md))

**Gave up/new risk**:
- **Citations are a convention, not a guarantee.** The sources are numbered and the model is asked to reference them, but nothing checks that it did so honestly. It can attribute a claim to source 2 when source 2 does not support it, and the answer will look exactly the same as a correct one. Only reading the source alongside the answer catches that. This is the real cost of the decision, and it is a permanent one, not a bug to be fixed later
- On the free allowance the provider may use what is sent to improve its own products, and people there may read it. What is sent is invented demo payments and this project's own documents, so nothing is at stake — but the same code pointed at real payment data would be a genuine problem, and nothing in the code would stop it
- The free allowance has limits on how often it can be called. Fine for a demonstration, not for anything sustained

**Revisit if**: this is ever pointed at real data, in which case both the data-use terms and the weak citations become disqualifying rather than acceptable. Also worth revisiting if answers start being trusted rather than checked, because that is the point at which Option A's stronger citations are worth paying for.
