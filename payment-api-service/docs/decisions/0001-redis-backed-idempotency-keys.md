# ADR-001: Redis-backed idempotency keys for POST /transactions

## Status

Accepted

## Date

2026-09-02

## Context

`POST /transactions` created a new row on every call. A client that retried —
because the response was lost, the connection dropped, or a queued job was
redelivered — created a second, duplicate payment. Nothing in the request let
the server recognise a retry as the *same* intent.

Constraints at the time of the decision:

- This is a payments endpoint. Creating a payment twice is far worse than
  rejecting a legitimate one, so any ambiguity should resolve toward refusing.
- Postgres and Flyway were already in the stack. There was no Redis, no cache
  abstraction, no interceptors, filters, or AOP beyond the JWT filter.
- Transactions are already party-scoped by `senderId`/`recipientId`, so a
  per-user notion of ownership already existed to build on.

## Decision

Require an `Idempotency-Key` header on `POST /transactions`, backed by Redis.

1. **The header is required.** Missing or malformed keys return `400` using the
   same `errors` map that `handleMethodArgumentNotValid` already produces, so
   clients see one validation format.
2. **Keys are namespaced per authenticated user** — `idempotency:{userId}:{key}`
   — so two users cannot collide by picking the same key.
3. **Reserve before writing, not after.** The request claims the key with an
   atomic `SETNX` *before* the Postgres insert, then overwrites it with the
   response after. See the consequences section for why the order is the whole
   decision.
4. **24-hour TTL**, preserved across state transitions with `KEEPTTL` so the
   window runs from the first request rather than restarting on each write.
5. **The request fingerprint is a SHA-256 of canonicalised fields**, not of the
   raw body. A retry whose fingerprint differs is rejected with `422`.
6. **A replay returns the original status** plus `Idempotent-Replay: true`, so
   clients can distinguish a replay from a fresh create.

## Alternatives Considered

### Postgres table with `UNIQUE (user_id, key)`

- Pros: no new infrastructure or runtime dependency; the key row and the
  transaction insert commit **atomically in one transaction**, which removes the
  dual-store crash window entirely; expiry via a partial index or scheduled job.
- Cons: no native TTL; adds a table and a cleanup concern; a hot key row on a
  write path.
- Rejected: the atomicity argument is genuinely stronger, and this was raised
  explicitly at design time. Redis was chosen deliberately for native TTL and to
  keep the payments table free of coordination rows. **If the dual-store crash
  window ever causes a real incident, this is the alternative to revisit.**

### Record the key *after* the insert commits

- Pros: simplest possible implementation; one Redis write instead of three.
- Cons: a crash between the commit and the Redis write loses the key, and the
  retry duplicates the payment. Two concurrent retries also both read "no key"
  and both insert.
- Rejected: it fails in exactly the direction this feature exists to prevent.

### Hash the raw request body

- Pros: no per-DTO code; works for any endpoint automatically.
- Cons: `{"amount":"50.00"}` and `{"amount":"50.0"}` are the same payment but
  different bytes, as are two bodies with reordered JSON keys. Both would be
  rejected as `422` against a client doing nothing wrong.
- Rejected: the fingerprint must reflect intent, not encoding. Request DTOs
  implement `IdempotentRequest.canonicalForm()` instead.

### A distributed lock (Redis lock or Postgres advisory lock)

- Pros: familiar mutual-exclusion model.
- Cons: solves only concurrency, not durability — it gives no way to *replay*
  the original response to a retry arriving after the lock is released.
- Rejected: the requirement is "return the original response", not merely
  "serialize the writes".

## Consequences

- **Redis is now a hard runtime dependency of `POST /transactions`.** If Redis
  is unavailable the reservation fails and the endpoint returns `500` rather
  than accepting an unprotected write. This is deliberate: failing closed beats
  silently duplicating a payment.
- **A residual crash window remains** between the Postgres commit and the
  completing Redis write. The key stays `IN_PROGRESS` and retries receive `409`
  until the TTL expires. This never duplicates a payment, which is the safe
  direction, but it is the cost of using two stores without a shared
  transaction. A single-store design (see the Postgres alternative) would not
  have it.
- **24 hours of keys is real memory.** The TTL bounds growth, but Redis needs
  headroom for a day's POST volume, and `maxmemory-policy` must not be an
  eviction policy that can drop live keys early.
- **Measured cost:** the layer adds ~0.68 ms at p99 to a create (1.33 ms →
  2.01 ms), measured by `./gradlew benchmark`. A replayed retry costs ~0.5 ms
  p99 because it makes two Redis calls and no SQL query at all.
- **Verified behaviour:** 0 duplicate rows under 10, 50, and 200 simultaneous
  retries of the same key (`ConcurrentRetryTests`), and 50 distinct keys still
  produce 50 rows, so idempotency does not over-collapse.
- Clients must treat a corrected request as a new request — see ADR-002.
