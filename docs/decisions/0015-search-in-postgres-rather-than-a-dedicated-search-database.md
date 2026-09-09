# ADR-0015: Keep the search inside Postgres rather than adding a dedicated search database

**Date:** 09-09-2026

**Service:** insights-service

## Context

To answer a question, the insights service first has to find the handful of documents most likely to contain the answer. It does that by comparing the question against every stored piece of text and keeping the closest few.

There is a whole category of database built specifically for this comparison. Adding one is the obvious move, and it is worth being precise about why it was not made here.

The size of the problem matters more than anything else. The whole corpus is around a thousand pieces of text — every ADR, the README, the API reference, and one entry per payment that has passed through the system. Each piece is represented by a list of 384 numbers. All of it together is about a megabyte and a half.

At that size, checking every single row and keeping the closest is over in well under a millisecond. Any option would be fast enough. So this is not a decision about speed, and treating it as one would be dishonest.

## Decision

The search lives in a third Postgres database, `insights_db`, using the `pgvector` extension, named after what it holds like the other two.

Deliberately no index on the numbers. At this size, checking every row beats maintaining an index, and it is one less thing to explain.

## Alternatives considered

#### Option A - Run a purpose-built search database alongside the others

**How it works**: add a container running one of the databases designed for exactly this kind of comparison, and have the service talk to it instead

**Pros**:
- Built for the job, and would stay fast at sizes far beyond anything here
- Comes with extras this has to do by hand later, such as combining a keyword search with a meaning-based one

**Cons**:
- A new service, a new client library, a new way of asking questions and a new set of ways things can fail, bought for no gain at this size
- The service already needs an ordinary database for the exact-match half of its search ([ADR-0017](0017-two-kinds-of-search-instead-of-one.md)). Adding a second one means two places to ask, and joining the answers by hand
- One more thing to run, back up and reason about, in a project whose whole point is that one command brings it all up

#### Option B - Use a hosted search service

**How it works**: send the numbers to a paid service that stores and searches them elsewhere

**Pros**:
- Nothing extra to run locally
- Scales without any thought

**Cons**:
- An account, a key, and a bill, for a project that otherwise runs entirely on one laptop with no outside dependencies
- The corpus includes this project's own logs. Sending them to a third party is a decision that should be made deliberately, not as a side effect of picking a database
- Cannot be demonstrated offline

#### Option C - Keep everything in memory and skip the database

**How it works**: load all the numbers into the service when it starts and compare them there

**Pros**:
- Genuinely fast enough — this is not a compromise at this size, it would work perfectly well
- No third database at all, and the simplest thing that could possibly work

**Cons**:
- The exact-match half of the search needs somewhere to look things up by payment id, so a database is needed regardless. Having one anyway makes the in-memory copy redundant
- Everything is rebuilt on every restart, and there is no way to inspect what is stored without adding one

## Consequences

**Gained**:
- A third container of an image already in the stack, following the same setup and the same health check as the other two
- The exact match and the meaning-based search hit one table in one place, so one question is one round trip rather than two
- Nothing new to learn to inspect what is stored — the same `psql` that works on the other two databases works here

**Gave up/new risk**:
- No built-in way to combine keyword matching with meaning-based matching, which the purpose-built options give for free. If that is ever wanted, it has to be written by hand
- No re-ranking of results, and no clever handling of a search that is narrowed down first and compared second. These get slow at large sizes; at a thousand rows they do not apply
- The search shares its resources with everything else in that database, which would matter if it were also serving something busy

**Revisit if**: the corpus approaches a million entries, or a keyword search has to be blended with the meaning-based one. Moving is cheap either way: the numbers can be regenerated from the original text at any time, so a migration is a re-run of the ingestion step rather than a data export.
