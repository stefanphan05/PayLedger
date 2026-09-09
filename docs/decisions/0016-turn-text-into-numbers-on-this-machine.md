# ADR-0016: Turn text into numbers on this machine, not through a paid service

**Date:** 09-09-2026

**Service:** insights-service

## Context

Before anything can be searched by meaning, every piece of text has to be turned into a list of numbers that stands for what it says. Two pieces of text about the same subject end up with similar lists, and that similarity is what the search actually measures.

Something has to produce those numbers. There are two ways to get them: run a model on this machine, or send the text to a service that returns them.

One thing is worth writing down because it surprises people: the company whose language model was originally going to write the answers does not offer this at all. It writes text, it does not produce these lists. So "use the same provider for everything" was never on the table, and a separate decision had to be made regardless.

## Decision

A small model runs inside the insights service itself and produces the numbers locally. It is downloaded once when the container image is built, so a running container never reaches the internet for it.

Each piece of text becomes a list of 384 numbers.

## Alternatives considered

#### Option A - Send the text to a specialist provider

**How it works**: call a service built specifically for this, and store the lists it returns

**Pros**:
- Noticeably better at the job. The lists it produces separate related and unrelated text more cleanly, which shows up as better search results
- Handles far longer pieces of text in one go
- Nothing large to install, and a much smaller container image

**Cons**:
- Another account, another key and another company in the loop, for a project that otherwise runs on one laptop
- One call per piece of text. Rebuilding the whole corpus becomes a network operation that can half-fail and leave the stored numbers inconsistent
- The corpus includes this project's own logs, so sending it somewhere is a decision that deserves to be made on purpose

#### Option B - Use the same provider that writes the answers

**How it works**: that provider does offer this separately, so one account could cover both jobs

**Pros**:
- One fewer company involved than Option A, and a key that already exists
- Same argument on quality as Option A

**Cons**:
- Still a network call for every piece of text, with the same half-failure problem
- Ties two unrelated decisions together. Changing who writes the answers would then also mean regenerating every stored number, and those two choices have no reason to move at the same time
- The free allowance is aimed at occasional use, and rebuilding the corpus is a burst of hundreds of calls at once

#### Option C - Run a much larger model locally

**How it works**: the same approach taken here, but with one of the bigger models, for better quality

**Pros**:
- Better results than the small model, with none of the network or account objections

**Cons**:
- Several gigabytes on top of an image that is already large
- Slow enough on a laptop without a graphics card that rebuilding the corpus stops being something done casually
- The corpus is small and the questions are known. The extra quality would not be visible against ten test questions

## Consequences

**Gained**:
- No key, no account, no bill and no network call. Rebuilding the corpus works on a plane
- The whole pipeline can be demonstrated with the internet switched off, right up to the point where an answer is written
- Nothing about this project's logs leaves the machine during this step

**Gave up/new risk**:
- The 384 is written into the database column that stores the numbers. Changing the model changes that count, which makes every stored value meaningless — so switching later means emptying the table and rebuilding it, not a gradual migration
- The model stops reading after roughly 190 English words and says nothing about it. Anything past that point is simply not represented, and there is no error to notice. This is the reason documents are split at the smaller headings rather than only the larger ones, and the reason the ingestion step prints a warning for every piece of text that is still too long. Ten of the current 121 are
- The machine learning library it needs is what makes the container image around 2 GB
- Results are meaningfully worse than a paid service would give. On this corpus that has not mattered yet, but it is a real gap and not a rounding error

**Revisit if**: search quality stops improving even after adjusting how documents are split up. That is the signal that the model rather than the chunking has become the limit, and at that point Option A is the sensible next step.
