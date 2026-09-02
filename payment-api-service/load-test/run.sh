#!/usr/bin/env bash
# End-to-end load test of POST /transactions.
#
#   ./load-test/run.sh [VUS] [DURATION]
#
# Brings up an isolated Postgres + Redis, boots the app against them, drives it
# with k6 (run via Docker, so k6 does not need to be installed), then tears the
# whole thing down. Your development database is never touched.
set -euo pipefail

VUS="${1:-50}"
DURATION="${2:-30s}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
COMPOSE="$HERE/docker-compose.loadtest.yml"
APP_PORT=8081
APP_LOG="$HERE/app.log"
APP_PID=""

cleanup() {
  [[ -n "$APP_PID" ]] && kill "$APP_PID" 2>/dev/null || true
  docker compose -f "$COMPOSE" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> starting Postgres and Redis"
docker compose -f "$COMPOSE" up -d --wait

echo "==> building the application jar"
(cd "$ROOT" && ./gradlew bootJar -q)
JAR="$(ls "$ROOT"/build/libs/*.jar | grep -v plain | head -1)"

echo "==> booting the app on :$APP_PORT"
java -jar "$JAR" \
  --server.port="$APP_PORT" \
  --spring.datasource.url="jdbc:postgresql://localhost:55432/payledger_load" \
  --spring.datasource.username=payledger \
  --spring.datasource.password=payledger \
  --spring.data.redis.host=localhost \
  --spring.data.redis.port=56379 \
  --spring.jpa.show-sql=false \
  --jwt.secret="$(printf 'load-test-secret-key-that-is-long-enough-for-hs256!' | base64)" \
  > "$APP_LOG" 2>&1 &
APP_PID=$!

echo -n "==> waiting for the app"
for _ in $(seq 1 90); do
  # Any HTTP response means the server is accepting connections; /auth/login
  # answers without a valid body, which is enough of a readiness signal.
  if curl -s -o /dev/null -m 2 "http://localhost:$APP_PORT/auth/login" 2>/dev/null; then
    echo " - up"; break
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo " - the app exited early:"; tail -30 "$APP_LOG"; exit 1
  fi
  echo -n "."; sleep 1
done

echo "==> running k6 (${VUS} VUs, ${DURATION} per scenario)"
docker run --rm -i \
  --add-host=host.docker.internal:host-gateway \
  -e BASE_URL="http://host.docker.internal:$APP_PORT" \
  -e VUS="$VUS" \
  -e DURATION="$DURATION" \
  grafana/k6 run - < "$HERE/idempotency.js"
