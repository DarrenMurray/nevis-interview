#!/usr/bin/env bash
# Start the built image and assert it actually serves traffic.
#
# The Dockerfile's assembly (COPY paths, flattened jar layers, the unprivileged user)
# cannot be verified by the Maven test suite — only by booting the image. This is that
# check, and it is what CI runs after building.
set -euo pipefail

IMAGE="${1:-nevis/search-api:dev}"
PORT="${2:-8080}"
DOCKER="${DOCKER:-docker}"
NAME="search-api-smoke-$$"
TIMEOUT="${TIMEOUT:-90}"

cleanup() {
    echo "--- container logs (tail) ---"
    $DOCKER logs "$NAME" 2>&1 | tail -30 || true
    $DOCKER rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Starting $IMAGE as $NAME on port $PORT"
$DOCKER run -d --name "$NAME" -p "${PORT}:8080" "$IMAGE" >/dev/null

for i in $(seq 1 "$TIMEOUT"); do
    code="$(curl -fsS -o /dev/null -w '%{http_code}' "http://localhost:${PORT}/search?q=smoke" 2>/dev/null || true)"
    if [ "$code" = "200" ]; then
        echo "PASS: GET /search returned 200 after ${i}s"
        exit 0
    fi
    # Fail fast rather than waiting out the timeout if the container has already died.
    running="$($DOCKER inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null || echo false)"
    if [ "$running" != "true" ]; then
        echo "FAIL: container is no longer running" >&2
        exit 1
    fi
    sleep 1
done

echo "FAIL: no 200 from /search within ${TIMEOUT}s" >&2
exit 1
