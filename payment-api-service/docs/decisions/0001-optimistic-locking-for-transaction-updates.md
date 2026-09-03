# ADR-0001: Use optimistic locking for transaction updates
**Date:** 20-09-2026
## Context
Two concurrent requests can update the same `transactions` row (e.g. a status update racing a retry). Rare conflicts expected, this isn't a high-contention resource like a shared inventory counter.
## Decision
Added a `@Version` column to `Transaction`. Concurrent writes fail fast with `ObjectOptimisticLockingFailureException` instead of silently overwriting each other; the caller retries.
## Alternatives considered
#### Option A - Pessimistic locking
**How it works**: (`{sql} SELECT ... FOR UPDATE`) locks the row the moment it's read. Any other transaction trying to touch that same row has to wait until the lock is released, no conflict can happen because nobody else gets in until you're done.
**Pros**: Guarantees no conflict
**Cons**:
- Holds a DB lock for the duration of the transaction. 
- A slow transaction holds the lock the whole time, so other requests queue up waiting behind it, one slow write can stall everything else touching that row.
## Consequences
**Gained**: no lock contention on the hot path, conflicts are cheap and explicit.
**Gave up/new risk**: callers must handle the exception and retry, pushes complexity to the client side.
**Revisit if**: this table becomes high-contention (e.g. many writers hitting the same row), at which point pessimistic locking would win instead.