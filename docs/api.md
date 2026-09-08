# PayLedger API Reference

The `payment-api-service` is the only service exposing an HTTP surface. `ledger-service`
is internal and reachable only over Kafka, it has no endpoints.

- **Base URL (local):** `http://localhost:8080`
- **Content type:** `application/json` on requests; `application/json` on success and
  `application/problem+json` on errors.
- **Auth:** stateless JWT bearer tokens. Every endpoint requires one except
  `POST /auth/signup` and `POST /auth/login`.

## Authentication

Obtain a token from `POST /auth/login`, then send it on every subsequent call:

```
Authorization: Bearer <token>
```

The prefix is matched case-insensitively. Tokens are HMAC-signed and expire after
1 hour (`jwt.expiration-ms`); `expiresIn` on the login response reports the lifetime
in **seconds**. There is no refresh endpoint, log in again when the token expires.

A missing, malformed, expired, or unknown-user token leaves the request
unauthenticated, which the security entry point renders as `401 Unauthorized`. It is
never a distinct error, the API does not tell a client *why* a token was rejected.

## Error format

All errors are RFC 7807 `ProblemDetail` responses:

```json
{
  "type": "about:blank",
  "title": "Recipient Not Found",
  "status": 404,
  "detail": "Recipient 8f2c… not found",
  "instance": "/transactions"
}
```

Validation failures add an `errors` object mapping each rejected field to its
messages. The same shape is used for body validation, malformed JSON, and bad
headers, so a client only ever parses one format:

```json
{
  "title": "Validation Error",
  "status": 400,
  "detail": "Request validation failed",
  "errors": {
    "amount": ["Amount must be greater than zero"],
    "currencyCode": ["Currency code must be a 3-letter ISO 4217 code (e.g. USD)"]
  }
}
```

### Errors common to every endpoint

| Status | Title | When |
|---|---|---|
| 400 | Validation Error / Malformed Request | Body fails `@Valid`, or JSON is unparseable / a required field is absent |
| 401 | Unauthorized | No valid bearer token on a protected endpoint |
| 403 | Forbidden | Authenticated but lacking the required role |
| 409 | *(no title)* | `Transaction was modified concurrently, retry` — optimistic lock clash (ADR-0001) |
| 500 | *(no title)* | `An unexpected error occurred` — unhandled server fault |

---

## `POST /auth/signup`

Registers a user. Public. New users always receive the `USER` role; `ADMIN` is granted
out-of-band (there is no endpoint for it).

**Request**

| Field | Type | Constraints |
|---|---|---|
| `firstName` | string | required, ≤ 50 chars |
| `lastName` | string | required, ≤ 50 chars |
| `email` | string | required, valid address, ≤ 255 chars — stored trimmed and lowercased |
| `password` | string | required, 8–72 chars (bcrypt's input limit) |

```bash
curl -X POST http://localhost:8080/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Ada","lastName":"Lovelace","email":"ada@example.com","password":"correct-horse"}'
```

**`201 Created`**

```json
{
  "id": "3f1b7a2e-...",
  "firstName": "Ada",
  "lastName": "Lovelace",
  "email": "ada@example.com",
  "createdAt": "2026-09-08T10:15:30Z"
}
```

No token is issued — call `/auth/login` next.

**Errors:** `409 Email Already In Use` — `Email <email> is already registered`.

---

## `POST /auth/login`

Exchanges credentials for a token. Public.

**Request:** `email`, `password` — both required, non-blank. Email is trimmed and
lowercased before lookup, so casing on sign-up does not matter.

```bash
curl -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"correct-horse"}'
```

**`200 OK`**

```json
{ "token": "eyJhbGciOi...", "tokenType": "Bearer", "expiresIn": 3600 }
```

**Errors:** `401 Unauthorized` — `Invalid email or password`. Deliberately identical
for an unknown email and a wrong password, so the endpoint cannot be used to
enumerate registered accounts.

---

## `GET /auth/me`

Returns the authenticated user, including roles — the only way for a client to learn
whether it may call the admin endpoint.

```bash
curl http://localhost:8080/auth/me -H "Authorization: Bearer $TOKEN"
```

**`200 OK`**

```json
{
  "id": "3f1b7a2e-...",
  "firstName": "Ada",
  "lastName": "Lovelace",
  "email": "ada@example.com",
  "roles": ["USER"],
  "createdAt": "2026-09-08T10:15:30Z"
}
```

---

## `POST /transactions`

Creates a payment from the authenticated user to `recipientId`. The sender is always
taken from the token — it cannot be set in the body.

This endpoint returns **before the money moves**. It records the intent, returns
`PENDING`, and publishes an event through the transactional outbox (ADR-0004);
`ledger-service` performs the double-entry posting asynchronously and the status
later becomes `COMPLETED` or `FAILED`. Poll `GET /transactions/{id}` for the outcome.

**Headers**

| Header | Required | Notes |
|---|---|---|
| `Idempotency-Key` | yes | 8–255 chars, `[A-Za-z0-9_-]` only. Scoped per user, so two callers may pick the same value without colliding. |

**Request**

| Field | Type | Constraints |
|---|---|---|
| `amount` | decimal | required, ≥ 0.01, ≤ 15 integer and 4 fractional digits |
| `currencyCode` | string | required, 3 uppercase letters (ISO 4217) |
| `recipientId` | UUID | required, must exist, must not be the sender |

There is no user-lookup endpoint — the caller must already hold the recipient's UUID.

```bash
curl -X POST http://localhost:8080/transactions \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 7f3a91cc-checkout-42' \
  -d '{"amount":"15.00","currencyCode":"USD","recipientId":"9c2d5b10-..."}'
```

**`201 Created`**

```json
{
  "id": "d41f0c88-...",
  "amount": "15.00",
  "currency": "USD",
  "status": "PENDING",
  "senderId": "3f1b7a2e-...",
  "recipientId": "9c2d5b10-...",
  "createdAt": "2026-09-08T10:22:04Z",
  "failureReason": null
}
```

`amount` is serialised as a **JSON string** to avoid float rounding; parse it with a
decimal type, not a double.

`failureReason` is `null` unless `status` is `FAILED`, and is free text — treat it as a
message to show, not a code to branch on. Today `ledger-service` sends
`INSUFFICIENT_FUNDS`, `CURRENCY_MISMATCH` or `SELF_TRANSFER`, and may add more.

### Idempotency

Retrying with the same key replays the original outcome for **24 hours**
(`idempotency.ttl`) instead of creating a second payment. A replayed success carries
`Idempotent-Replay: true`; a replayed rejection is byte-identical to the first one,
because errors are rendered by the exception handler, which sets no headers. The
header is informational, the outcome is the same either way.

The key is bound to the body it was first used with, compared on a canonical form, so
`"50.00"` and `"50.0"` count as the same request. **A client that corrects a rejected
request and retries with the same key gets 422, not a fresh attempt, a corrected
request needs a new key.**

When a request fails ambiguously (a connection drop at commit time, where the write
may or may not have landed), the key is held for 60 seconds
(`idempotency.ambiguous-failure-hold`) and retries get `409` during that window. This
is deliberate: releasing the key would let a retry create a duplicate payment. Wait
out the hold and retry with the same key.

See `docs/decisions/0002-redis-backed-idempotency-keys.md` for the design.

**Errors**

| Status | Title | When |
|---|---|---|
| 400 | Validation Error | `Idempotency-Key` blank, wrong length, or has disallowed characters |
| 400 | Missing Header | `Idempotency-Key` absent entirely |
| 400 | Invalid Transfer | `Cannot send a transaction to yourself` |
| 404 | Recipient Not Found | `recipientId` matches no user |
| 409 | Request In Progress | An earlier request with this key is still running, or is inside the ambiguous-failure hold. Retry with the **same** key after a short delay. |
| 422 | Idempotency Key Reused | This key was already used with a different body. Use a new key. |
| 500 | Replay Unavailable | A stored failure cannot be reconstructed (a server bug). Retry with a new key. |

---

## `GET /transactions/{transactionId}`

Fetches one transaction. A regular user may read a transaction only if they are the
sender or the recipient; an `ADMIN` may read any.

An unauthorised transaction returns `404`, not `403`, the API does not confirm that
a transaction exists to someone who is not a party to it.

```bash
curl http://localhost:8080/transactions/d41f0c88-... -H "Authorization: Bearer $TOKEN"
```

**`200 OK`** — same shape as the create response.

This is the endpoint to poll for the outcome. A payment is created `PENDING` and
usually settles within a few seconds, once `ledger-service` has posted it and the
verdict has travelled back. `COMPLETED` and `FAILED` are final — nothing moves a
transaction out of them.

**Errors:** `404 Transaction Not Found` — `Transaction with id <id> was not found`
(also returned when the caller is not a party to it).

---

## `GET /transactions`

Lists transactions where the authenticated user is sender **or** recipient. Admins
get their own transactions here, not everyone's.

**Query parameters**

| Param | Default | Notes |
|---|---|---|
| `page` | `0` | Zero-based |
| `size` | `20` | |
| `sort` | `createdAt,desc` | Standard Spring Data syntax, e.g. `sort=amount,asc` |

```bash
curl "http://localhost:8080/transactions?page=0&size=20&sort=createdAt,desc" \
  -H "Authorization: Bearer $TOKEN"
```

**`200 OK`**

```json
{
  "content": [ { "id": "d41f0c88-...", "amount": "15.00", "currency": "USD", "status": "COMPLETED", "senderId": "3f1b7a2e-...", "recipientId": "9c2d5b10-...", "createdAt": "2026-09-08T10:22:04Z" } ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

---

## `PATCH /transactions/{transactionId}/status`

Forces a transaction into a given status. **Requires the `ADMIN` role.**

This is an operational override, not the normal path — statuses are meant to be set
by `ledger-service` through the event pipeline. It publishes a
`PAYMENT_STATUS_CHANGED` event like any other update.

**Request**

| Field | Type | Values |
|---|---|---|
| `status` | enum | `PENDING`, `COMPLETED`, `FAILED` |

```bash
curl -X PATCH http://localhost:8080/transactions/d41f0c88-.../status \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"status":"FAILED"}'
```

**`200 OK`** — the updated transaction.

**Errors:** `403 Forbidden` — `You are not allowed to do that` (caller is not an
admin); `404 Transaction Not Found`; `409` on a concurrent-modification clash.

---

## Reference

### Transaction status

| Status | Meaning |
|---|---|
| `PENDING` | Accepted and queued; `ledger-service` has not posted it yet |
| `COMPLETED` | Funds moved and the double-entry posting committed |
| `FAILED` | Rejected by the ledger (e.g. insufficient funds) |

`PENDING` is the only status `POST /transactions` ever returns.

### Roles

| Role | Grants |
|---|---|
| `USER` | Default on sign-up. Own transactions only. |
| `ADMIN` | Reads any transaction; may `PATCH` a status. Assigned directly in the database. |
