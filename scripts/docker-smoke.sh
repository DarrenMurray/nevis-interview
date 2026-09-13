#!/usr/bin/env bash
# End-to-end check of the compose stack: brings it up, asserts the three core behaviours
# against the running containers, then tears it down.
#
# The application requires Postgres, so a lone container cannot be smoke tested. Driving
# compose also exercises the Dockerfile, the migrations and the healthcheck ordering.
set -euo pipefail

PORT="${1:-8080}"
DOCKER="${DOCKER:-docker}"
TIMEOUT="${TIMEOUT:-180}"
BASE="http://localhost:${PORT}"

passed=0
failed=0

pass() { printf '  PASS  %s\n' "$1"; passed=$((passed + 1)); }
fail() { printf '  FAIL  %s\n' "$1" >&2; failed=$((failed + 1)); }

cleanup() {
    status=$?
    if [ "$status" -ne 0 ] || [ "$failed" -ne 0 ]; then
        echo "--- api logs ---"
        $DOCKER compose logs --tail 40 api 2>&1 || true
        echo "--- db logs ---"
        $DOCKER compose logs --tail 15 db 2>&1 || true
    fi
    # -v so each run starts from an empty database and the migrations are exercised.
    $DOCKER compose down -v >/dev/null 2>&1 || true
    exit $(( status != 0 ? status : (failed > 0 ? 1 : 0) ))
}
trap cleanup EXIT

query() { curl -fsS "${BASE}/search/$1?q=$2" 2>/dev/null || echo '[]'; }

echo "Starting stack"
$DOCKER compose up --build -d

for i in $(seq 1 "$TIMEOUT"); do
    if [ "$(curl -fsS -o /dev/null -w '%{http_code}' "${BASE}/search?q=smoke" 2>/dev/null || true)" = "200" ]; then
        break
    fi
    if [ "$($DOCKER compose ps -q api | wc -l)" -eq 0 ]; then
        echo "api container exited during startup" >&2
        exit 1
    fi
    sleep 1
    if [ "$i" -eq "$TIMEOUT" ]; then
        echo "no 200 from /search within ${TIMEOUT}s" >&2
        exit 1
    fi
done

# The API serves traffic before the embedding backfill finishes, so the semantic checks below
# would run against a partially embedded corpus. Wait for every document to have a vector.
for i in $(seq 1 "$TIMEOUT"); do
    pending=$($DOCKER compose exec -T db psql -U search_api -d search -tAc \
        "SELECT count(*) FROM documents WHERE embedding IS NULL" 2>/dev/null | tr -d '[:space:]')
    [ "${pending:-1}" = "0" ] && break
    sleep 1
    if [ "$i" -eq "$TIMEOUT" ]; then
        echo "embedding backfill did not finish within ${TIMEOUT}s (${pending} documents pending)" >&2
        exit 1
    fi
done

echo
echo "Schema"
tables=$($DOCKER compose exec -T db psql -U search_api -d search -tAc \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('clients','documents','document_chunks')" \
    2>/dev/null | tr -d '[:space:]')
[ "$tables" = "3" ] && pass "migrations applied (clients, documents, document_chunks)" \
                    || fail "expected 3 tables, found ${tables:-none}"

clients=$($DOCKER compose exec -T db psql -U search_api -d search -tAc \
    "SELECT count(*) FROM clients" 2>/dev/null | tr -d '[:space:]')
[ "${clients:-0}" -gt 0 ] && pass "seed data loaded (${clients} clients)" \
                          || fail "no seed data; V2__seed.sql did not apply"

unembedded=$($DOCKER compose exec -T db psql -U search_api -d search -tAc \
    "SELECT count(*) FROM documents WHERE embedding IS NULL" 2>/dev/null | tr -d '[:space:]')
embedded=$($DOCKER compose exec -T db psql -U search_api -d search -tAc \
    "SELECT count(*) FROM document_chunks" 2>/dev/null | tr -d '[:space:]')
[ "${unembedded:-1}" = "0" ] && [ "${embedded:-0}" -gt 0 ] \
    && pass "every document embedded (${embedded} passages)" \
    || fail "${unembedded} documents still unembedded"

echo
echo "Core use case 1: find clients by email, name or description"
query clients NevisWealth | grep -q 'john.doe@neviswealth.com' \
    && pass 'q=NevisWealth returns john.doe@neviswealth.com' \
    || fail 'q=NevisWealth did not return john.doe@neviswealth.com'

query clients pension | grep -q 'beatrice.okonkwo' \
    && pass 'q=pension matches on description alone' \
    || fail 'q=pension did not match a description'

echo
echo "Core use case 2: find documents by meaning"
# The corpus contains neither word of the query, so a hit can only be semantic.
corpus=$($DOCKER compose exec -T db psql -U search_api -d search -tAc \
    "SELECT count(*) FROM documents WHERE lower(title || ' ' || content) LIKE '%address proof%'" \
    2>/dev/null | tr -d '[:space:]')
[ "${corpus:-1}" = "0" ] && pass 'no document contains the phrase "address proof"' \
                         || fail 'a document contains the phrase, so a hit would not prove semantic search'

query documents 'address+proof' | grep -q 'Utility Bill' \
    && pass 'q=address proof returns the utility bill' \
    || fail 'q=address proof did not return the utility bill'

query documents 'zebra+unicorn+nonsense' | grep -q '^\[\]$' \
    && pass 'unrelated query returns no documents' \
    || fail 'unrelated query returned results; the relevance floor is not applied'

echo
echo "Core use case 3: summarise document content"
query documents 'address+proof' | python3 -c '
import json, sys
results = json.load(sys.stdin)
sys.exit(0 if results and all(r["document"].get("summary") for r in results) else 1)
' && pass "every document result carries a summary" \
  || fail "a document result had no summary"

query documents 'address+proof' | python3 -c '
import json, sys
results = json.load(sys.stdin)
# Extractive: the opening sentence of each summary must appear in its own document.
ok = all(r["document"]["summary"].split(". ")[0] in r["document"]["content"] for r in results)
sys.exit(0 if results and ok else 1)
' && pass "summaries are extracted from the document text" \
  || fail "a summary contained text absent from its document"

echo
echo "${passed} passed, ${failed} failed"
