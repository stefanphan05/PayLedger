# Load testing `POST /transactions`

Measures what the idempotency layer costs at the HTTP boundary, and how much
cheaper a replayed retry is than a real create.

## Run it

```bash
./load-test/run.sh              # 50 VUs, 30s per scenario
./load-test/run.sh 30 20s       # 30 VUs, 20s per scenario
```

k6 does not need to be installed — it runs from the `grafana/k6` Docker image.
The script brings up its own Postgres (`:55432`) and Redis (`:56379`), boots the
app on `:8081` against them, and tears everything down afterwards, so your
development database is never touched.

## What the two scenarios mean

| Scenario | Key per request | Path exercised |
| -------- | --------------- | -------------- |
| `create` | unique | SETNX reservation + 2 KEEPTTL writes + Postgres insert |
| `replay` | one fixed key | SETNX miss + GET, **no SQL at all** |

The unique key in `create` is load-bearing. Reuse one key and every request
after the first takes the replay branch, which is far cheaper — you would be
benchmarking the wrong path and reporting a number that flatters the design.

## Reading the output

`create_latency` and `replay_latency` are custom trends kept separate on
purpose: averaging them together would hide the difference the test exists to
show. `http_req_duration` mixes both plus the auth calls, so prefer the custom
trends.

A small number of `dial: i/o timeout` warnings at high VU counts are the Docker
bridge on macOS running out of ephemeral connections, not application errors —
they show up as connection failures rather than 5xx responses. Drop the VU count
if you want a completely clean run.

## Measuring the overhead precisely

k6 measures the endpoint end to end, so its numbers include HTTP, JSON and the
network. To isolate what the idempotency layer alone adds, run the JVM benchmark
instead — it executes the same write with and without the layer, interleaved,
against real Postgres and Redis:

```bash
./gradlew benchmark
```

It is tagged `benchmark` and excluded from `./gradlew test`.
