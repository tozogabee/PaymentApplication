#!/usr/bin/env bash
#
# Ad-hoc concurrency check — fires parallel HTTP requests to simulate real-world concurrent load.
# The Bruno collection runs sequentially by design, so this lives outside it.
#
# Usage:
#   1. start the app, e.g.  docker compose up -d --build --wait
#   2. ./scripts/concurrency-check.sh
#      # or against another host:  BASE=http://host:8080 THREADS=20 ./scripts/concurrency-check.sh
#
# Requires: curl, jq.
# Exit code: 0 if the optimistic-locking invariant holds (Scenario 1), non-zero otherwise.
# Scenario 2 is an informational demonstration and does not affect the exit code.

set -euo pipefail

BASE=${BASE:-http://localhost:8080}
THREADS=${THREADS:-10}

command -v jq  >/dev/null || { echo "jq is required (e.g. 'brew install jq')"; exit 2; }
curl -fsS "$BASE/actuator/health" >/dev/null || { echo "app not reachable at $BASE"; exit 2; }

body() { printf '{"amount":%s,"currency":"EUR","debtorAccount":"%s","creditorAccount":"%s"}' "$1" "$2" "$3"; }

# ---------------------------------------------------------------------------
echo "== Scenario 1: $THREADS concurrent UPDATES to the same payment =="
id=$(curl -fsS -X POST "$BASE/payments" -H 'Content-Type: application/json' \
       -d "$(body 100.0 "CC-UPD-$RANDOM" CC-CRED)" | jq -r .id)

codes=$(seq "$THREADS" | xargs -P "$THREADS" -I{} \
  curl -s -o /dev/null -w '%{http_code}\n' -X PUT "$BASE/payments/$id" \
    -H 'Content-Type: application/json' -d "$(body 150.0 CC-UPD CC-CRED)")

ok=$(grep -c '^200$' <<<"$codes" || true)
conflict=$(grep -c '^409$' <<<"$codes" || true)
echo "   results: 200=$ok  409=$conflict  (expected: 200=1)"
curl -fsS -X DELETE "$BASE/payments/$id" >/dev/null || true    # cleanup

if [ "$ok" -ne 1 ]; then
  echo "   FAIL: optimistic locking did not hold (expected exactly one successful update)"
  exit 1
fi
echo "   PASS: exactly one update won; the rest returned 409 (no lost update)"

# ---------------------------------------------------------------------------
echo
echo "== Scenario 2: $THREADS concurrent identical CREATES (duplicate-race demo) =="
debtor="CC-RACE-$RANDOM"
seq "$THREADS" | xargs -P "$THREADS" -I{} \
  curl -s -o /dev/null -X POST "$BASE/payments" \
    -H 'Content-Type: application/json' -d "$(body 9.0 "$debtor" CC-CRED)" || true

created=$(curl -fsS "$BASE/payments" | jq --arg d "$debtor" \
  '[.[] | select(.debtorAccount==$d and .status=="CREATED")] | length')
echo "   CREATED rows for $debtor: $created"
echo "   (main is best-effort check-then-insert: >1 is possible under a race;"
echo "    the race-proof branch — partial unique index + retry — would always give exactly 1.)"

# cleanup any rows the demo created
for pid in $(curl -fsS "$BASE/payments" | jq -r --arg d "$debtor" '.[] | select(.debtorAccount==$d) | .id'); do
  curl -fsS -X DELETE "$BASE/payments/$pid" >/dev/null || true
done

echo
echo "Done. Scenario 1 is the pass/fail gate; Scenario 2 is informational."