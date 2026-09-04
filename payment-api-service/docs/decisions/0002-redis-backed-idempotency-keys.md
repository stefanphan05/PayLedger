# ADR-002: Redis-backed idempotency keys for POST /transactions
**Date**: 02-09-2026
## Context
**Problem**: `POST /transactions` created a new row on every call. If the client retried, because the response was lost, connection dropped, ..., created a second duplicate payment. Nothing in the request let the server recognise a retry as the same intent

**Constrains**:
- This is a payments endpoint, creating a payment twice is far worse then rejecting a legitimate one.
- Postgres and Flyway were already in the stack. There was no Redis, no cache abstraction.
## Decision
Require an `Idempotency-Key` header on `POST /transactions`, backed by Redis.
1. **The header is required.** Missing or malformed keys return `400` using the
   same `errors` map that `handleMethodArgumentNotValid` already produces, so clients see one validation format.
2. **Keys are namespaced per authenticated user** — `idempotency:{userId}:{key}` so two users cannot collide by picking the same key.
3. **Reserve before writing, not after.** The request claims the key with an
   atomic `SETNX` *before* the Postgres insert, then overwrites it with the
   response after. See the consequences section for why the order is the whole
   decision.
4. **24-hour TTL**, preserved across state transitions with `KEEPTTL` so the
   window runs from the first request rather than restarting on each write.
## Alternatives considered
#### Option A -  Postgres table with `UNIQUE (user_id, key)`
**How it works**: insert a row for each key. If fails (duplicate), someone already used that key, return the old response instead.
**Pros**: no new infra, very durable, easy to query later
**Cons**: 
- Slower (already load test using this approach (91ms p99) and redis-backed idempotency approach (16ms p99)
- Hits the same database doing the real payment writes (shouldn't)
- No built-in expiry → gotta build a expiry service and cleanup after expired
#### Option B - In-memory cache, no Redis at all
**How it works**: Each app instance keeps a local hash map/cache of `key -> response`, checked and set directly in memory, no network call at all
**Pros**: Fastest option, zero network hop, nothing extra to run or deploy
**Cons**: 
- Only works if there's exactly one instance of payment-api-service running. If scaling to multiple instances, each instance has its own separate cache, a retry that happens to land on a different instance won't see the key at all, so the duplicates slips through.
- Every deploy wipes the cache clean
## Consequences
**Gained**:
- Correctness under retries, have tested it with 200 concurrent retries resulting in 0 duplicate transactions
- Fast (16ms p99) comparing to Postgres constraint alternative (91ms p99)
- Scales correctly across multiple instances, until the in-memory option
**Gave up / new risk**:
- New infra dependency, Redis is now a required piece of the system. If Redis is down, idempotency check fails
- Client is required to send a key with there request on the header
**Revisit if**:
- Redis becomes a reliability bottleneck
- Request volume grows enough that 24h TTL memory usage in Redis becomes a real cost concern 
