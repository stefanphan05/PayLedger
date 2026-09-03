# payment-api-service

A REST API for recording payments between users, built with Kotlin and Spring Boot.
Users sign up, authenticate with a JWT, and create transactions against other users;
every transaction is scoped so only its sender, its recipient, or an admin can see it.

## Quick start

You need JDK 17+ and Docker.

```bash
# 1. Config - the defaults already match the compose stack
cp .env.example .env

# 2. Postgres + Redis
docker compose up -d --wait

# 3. Run
./gradlew bootRun
```

`docker-compose.yml` provides everything the app needs to run: **Postgres 16** on
`:5432` (database `payment_db`) and **Redis 7** on `:6379`. Flyway creates the
schema on first start.

The config step comes first because `.env` feeds *both* Compose and Spring —
Compose reads `DB_USERNAME` / `DB_PASSWORD` from it to create the Postgres role,
and Spring reads the whole file via `spring.config.import`:

| Variable | Purpose |
| -------- | ------- |
| `DB_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/payment_db` |
| `DB_USERNAME`, `DB_PASSWORD` | Postgres credentials, used by Compose and the app |
| `JWT_SECRET` | Base64-encoded HS256 key |

Redis host and port default to `localhost:6379` and are overridable with
`REDIS_HOST` / `REDIS_PORT`.

Database contents live in the `pgdata` volume and survive `docker compose down`.
Use `docker compose down -v` to drop the volume and replay the Flyway migrations
from `V1` on the next start.

> **Already running Postgres or Redis locally? Stop them first.**
>
> ```bash
> lsof -nP -iTCP:5432 -sTCP:LISTEN    # anything here but Docker is a problem
> lsof -nP -iTCP:6379 -sTCP:LISTEN
> brew services stop postgresql@16    # or quit Postgres.app
> brew services stop redis
> ```
>
> A host service bound to `127.0.0.1` and Docker bound to `0.0.0.0` can coexist on
> the same port, and the host one wins for `localhost` connections. When that
> happens the container runs but goes unused — the app keeps working, silently
> against the wrong instance, so nothing looks broken. If you suspect it, check
> that the container is actually seeing traffic:
>
> ```bash
> docker compose exec redis redis-cli dbsize   # 0 after a POST /transactions = wrong Redis
> ```

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

- [ADR-001: Use optimistic locking for transaction updates](docs/decisions/0001-optimistic-locking-for-transaction-updates.md)
- [ADR-002: Redis-backed idempotency keys for POST /transactions](docs/decisions/0002-redis-backed-idempotency-keys.md)

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
