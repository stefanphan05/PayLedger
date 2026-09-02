# payment-api-service

A REST API for recording payments between users, built with Kotlin and Spring Boot.
Users sign up, authenticate with a JWT, and create transactions against other users;
every transaction is scoped so only its sender, its recipient, or an admin can see it.

## Quick start

You need JDK 17+ and Docker.

```bash
# 1. Redis (required by POST /transactions)
docker compose up -d redis

# 2. Postgres connection details and a JWT secret
cp .env.example .env        # then fill it in - see below

# 3. Run
./gradlew bootRun
```

`.env` is read by `spring.config.import` and holds:

| Variable | Purpose |
| -------- | ------- |
| `DB_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/payledger` |
| `DB_USERNAME`, `DB_PASSWORD` | Postgres credentials |
| `JWT_SECRET` | Base64-encoded HS256 key |

Redis host and port default to `localhost:6379` and are overridable with
`REDIS_HOST` / `REDIS_PORT`.

## Commands

| Command | Description |
| ------- | ----------- |
| `./gradlew bootRun` | Start the API on `:8080` |
| `./gradlew test` | Run the test suite (Testcontainers; needs Docker) |
| `./gradlew benchmark` | Run latency benchmarks, excluded from `test` |
| `./gradlew bootJar` | Build a runnable jar |
| `./load-test/run.sh` | End-to-end load test via k6 (see `load-test/README.md`) |

Tests start their own Postgres and Redis containers, so no local database is
needed to run them.

## Architecture

```
controller/   HTTP layer - reads headers, delegates, maps entities to DTOs
service/      Business logic and the idempotency decision tree
repository/   Spring Data JPA interfaces, plus the hand-written Redis repository
models/       dto/, entity/, enum/
exception/    One exception per file + GlobalExceptionHandler (RFC 7807)
security/     JWT filter, SecurityConfig, UserSecurity, request hashing
config/       Typed @ConfigurationProperties
```

Conventions worth knowing before contributing:

- **Errors are RFC 7807 `ProblemDetail`.** Every failure mode gets a dedicated
  exception in `exception/` and a handler that sets a `title`. Validation
  failures additionally carry an `errors` map of field to messages — new
  validation paths should match that shape.
- **DTOs are grouped by domain** in one file (`TransactionDtos.kt`), not one file
  per class. Response DTOs expose `companion object { fun from(entity) }`.
- **Schema changes go through Flyway** as `V<n>__snake_case.sql`.
- **Jackson 3** (`tools.jackson`), so beans are injected as `JsonMapper`, not
  `ObjectMapper` — and Spring Data Redis's Jackson 2 serializers are not usable.

### Decisions

Significant architectural decisions are recorded in `docs/decisions/`:

- [ADR-001: Redis-backed idempotency keys for POST /transactions](docs/decisions/0001-redis-backed-idempotency-keys.md)
- [ADR-002: Cache deterministic client errors, release the key on transient failures](docs/decisions/0002-cache-client-errors-release-transient-failures.md)

## API

| Method | Path | Notes |
| ------ | ---- | ----- |
| `POST` | `/auth/signup` | Public |
| `POST` | `/auth/login` | Public; returns a JWT |
| `GET` | `/auth/me` | Current user |
| `POST` | `/transactions` | **Requires an `Idempotency-Key` header** — see ADR-001 |
| `GET` | `/transactions` | Paged, scoped to the caller |
| `GET` | `/transactions/{id}` | Sender, recipient, or admin only |
| `PATCH` | `/transactions/{id}/status` | Admin only |
