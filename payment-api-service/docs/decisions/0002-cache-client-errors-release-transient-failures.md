# ADR-002: Cache deterministic client errors, release the key on transient failures

## Status

Accepted

## Date

2026-09-02

## Context

ADR-001 established that a request reserves its idempotency key *before* doing
any work. That leaves an open question: when the handler throws after the key is
already reserved, what happens to the key?

Two failure modes look identical to a `catch` block but demand opposite answers:

- `SelfTransferException` and `RecipientNotFoundException` are **deterministic**.
  The same request will be rejected the same way forever.
- A `DataAccessException` from a brief Postgres outage is **transient**. The same
  request would very likely succeed a second later.

A replay also needs the original error's HTTP status and title. Those live in
`GlobalExceptionHandler`, which runs *after* the service — so the service does
not have them at the moment it catches the exception.

## Decision

Split failures by determinism, using a marker interface on the exception itself.

1. **`exception/ClientError`** is a marker interface. `SelfTransferException` and
   `RecipientNotFoundException` implement it.
2. **Marked exceptions are stored as `FAILED`** with the exception's simple name
   and message, and replayed to a matching retry.
3. **Everything else keeps the key but shortens its TTL** to
   `idempotency.ambiguous-failure-hold` (60s). Retries inside that window get
   `409`; afterwards the client may retry normally.

   This started as an outright delete, which was wrong. `createTransaction` is
   not `@Transactional`, so `save()` commits on its own and a connection drop
   *at commit time* raises an exception even though the row may already exist.
   Deleting the key there let the retry create a second payment - precisely the
   failure this feature exists to prevent. Holding the key covers the window in
   which the outcome is genuinely unknown, without stranding the payment for a
   full day.
4. **A replay rethrows the original exception type**, rebuilt by
   `ClientErrorReplayer` from a small type-name registry. `GlobalExceptionHandler`
   then renders it through the *same* handler that produced the first response,
   so status and title are never duplicated anywhere.
5. **An unregistered type degrades to `409`**, never to a fabricated success and
   never to a silent re-execution.

## Alternatives Considered

### Cache every failure uniformly

- Pros: one rule, no marker interface, no classification to get wrong.
- Cons: a two-second Postgres blip would be cached for 24 hours. The client
  could never retry that payment with the same key even though retrying would
  now succeed — the payment is effectively poisoned.
- Rejected: it converts a recoverable outage into an unrecoverable one. The
  bounded hold in decision 3 is this idea with a survivable expiry.

### Release the key on every failure

- Pros: simplest; matches the first draft of the design, and gives the best
  availability during an outage.
- Cons: a client retrying a self-transfer re-executes the full validation path
  and hits Postgres every time, for an answer that cannot change.
- Rejected: it gives up a free optimisation, makes the `FAILED` state
  meaningless, and - as the code review found - releasing on *every* failure
  reopens the duplicate-payment hole whenever a commit fails ambiguously.

### Classify with a `when (e)` block inside the service

- Pros: no changes to existing exception classes.
- Cons: the list lives far from the exceptions it describes, so a new exception
  is cacheable only if someone remembers to edit a switch in another file. The
  default behaviour on forgetting is the *wrong* one.
- Rejected: the marker puts the decision on the class that knows the answer, and
  a new exception opts in where it is defined.

### Refactor exceptions to extend `ErrorResponseException`

- Pros: each exception would carry its own `HttpStatusCode` and `ProblemDetail`,
  so the service could read `e.statusCode` / `e.body` with zero duplication, and
  several `GlobalExceptionHandler` methods would become redundant.
- Cons: touches five existing, working exception classes and removes handler
  methods, well beyond the scope of adding idempotency.
- Rejected for now. **This is the cleaner long-term shape** — if the exception
  hierarchy is ever revisited, prefer it over the replay registry.

### Store the rendered `ProblemDetail` (status + title) in the record

- Pros: no registry, no exception reconstruction.
- Cons: status and title would then exist in two places — the advice and the
  stored record — free to drift, so a replayed error could disagree with a live
  one.
- Rejected: a second source of truth for HTTP semantics is worse than a registry.

## Consequences

- **The client contract changed.** A `FAILED` key only replays when the request
  fingerprint matches, so a client that *fixes* a rejected request and retries
  with the same key receives `422`, not a fresh attempt. This is correct — a key
  identifies one specific request — but it must be stated in the API docs:
  **a corrected request needs a new key.**
- **`ClientErrorReplayer`'s registry is the one hand-maintained list.** A new
  `ClientError` on this path that nobody registers degrades to `409` on replay:
  safe, but confusing. A reflective test asserting every `ClientError`
  implementation is registered would move that failure to build time.
- Classification depends on exceptions being marked accurately. Marking a
  transient failure as a `ClientError` would reintroduce the 24-hour poisoning
  this decision exists to prevent — see the note in `ClientError`'s KDoc.
- `EmailAlreadyInUseException` and `TransactionNotFoundException` are
  deliberately unmarked; nothing on this path throws them.

## References

- Builds on [ADR-001](0001-redis-backed-idempotency-keys.md).
