#!/usr/bin/env bash
# Bring up the compose stack and assert the API serves traffic.
#
# The app requires Postgres, so a lone container cannot be smoke-tested: Flyway fails at
# startup and the container exits. This drives compose instead, which is also what the
# Dockerfile's assembly, the migrations and the healthcheck ordering need to be checked
# against.
set -euo pipefail

PORT="${1:-8080}"
DOCKER="${DOCKER:-docker}"
TIMEOUT="${TIMEOUT:-180}"

cleanup() {
    status=$?
    if [ "$status" -ne 0 ]; then
        echo "--- api logs (tail) ---"
        $DOCKER compose logs --tail 40 api 2>&1 || true
        echo "--- db logs (tail) ---"
        $DOCKER compose logs --tail 15 db 2>&1 || true
    fi
    # -v so the next run starts from an empty database and migrations are exercised.
    $DOCKER compose down -v >/dev/null 2>&1 || true
    exit $status
}
trap cleanup EXIT

echo "Building and starting the stack..."
$DOCKER compose up --build -d

for i in $(seq 1 "$TIMEOUT"); do
    code="$(curl -fsS -o /dev/null -w '%{http_code}' "http://localhost:${PORT}/search?q=smoke" 2>/dev/null || true)"
    if [ "$code" = "200" ]; then
        echo "PASS: GET /search returned 200 after ${i}s"

        # The API answering proves Flyway ran, but assert the schema explicitly so a
        # migration silently doing nothing cannot pass as success.
        tables="$($DOCKER compose exec -T db psql -U search_api -d search -tAc \
            "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('clients','documents')" \
            2>/dev/null | tr -d '[:space:]')"
        if [ "$tables" != "2" ]; then
            echo "FAIL: expected clients and documents tables, found ${tables:-none}" >&2
            exit 1
        fi

        # Seed data is a migration, so an empty table means V2 did not run.
        rows="$($DOCKER compose exec -T db psql -U search_api -d search -tAc \
            "SELECT count(*) FROM clients" 2>/dev/null | tr -d '[:space:]')"
        if [ "${rows:-0}" -lt 1 ]; then
            echo "FAIL: no seed data - V2__seed.sql did not apply" >&2
            exit 1
        fi
        echo "PASS: schema migrated and seeded (${rows} clients)"
        exit 0
    fi
    if [ "$($DOCKER compose ps -q api | wc -l)" -eq 0 ]; then
        echo "FAIL: api container is gone" >&2
        exit 1
    fi
    sleep 1
done

echo "FAIL: no 200 from /search within ${TIMEOUT}s" >&2
exit 1
