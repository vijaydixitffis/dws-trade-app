#!/usr/bin/env bash
# End-to-end regression against a running system (docker compose up).
# Drives the capture API and checks the trade store through the query API.
# Requires: curl, jq. Dates are relative to today, so the script never goes stale.
set -euo pipefail

CAPTURE=${CAPTURE_URL:-http://localhost:8081}
QUERY=${QUERY_URL:-http://localhost:8083}
SIMULATOR=${SIMULATOR_URL:-http://localhost:8084}
DASHBOARD=${DASHBOARD_URL:-http://localhost:8080}
RUN="REG$(date +%s)"
FAILED=0

day() { # day <offset-in-days>  ->  yyyy-mm-dd
  date -u -d "$1 day" +%F 2>/dev/null || date -u -v"$1"d +%F
}
FUTURE=$(day +365)
YESTERDAY=$(day -1)

check() { # check <description> <expected> <actual>
  if [ "$2" == "$3" ]; then
    echo "PASS  $1"
  else
    echo "FAIL  $1: expected '$2', got '$3'"
    FAILED=1
  fi
}

post() { # post <tradeId> <version> <bookId> <maturity>  ->  HTTP status
  curl -s -o /dev/null -w '%{http_code}' -X POST "$CAPTURE/api/v1/trades" -H 'Content-Type: application/json' \
    -d "{\"tradeId\":\"$1\",\"version\":$2,\"counterPartyId\":\"CP-1\",\"bookId\":\"$3\",\"maturityDate\":\"$4\"}"
}

await() { # await <description> <jq-filter> <url> <expected>; polls for up to 60 s
  local actual=""
  for _ in $(seq 1 60); do
    actual=$(curl -sf "$3" | jq -r "$2" 2>/dev/null || true)
    [ "$actual" == "$4" ] && break
    sleep 1
  done
  check "$1" "$4" "$actual"
}

echo "Waiting for the services to be healthy..."
for url in "$CAPTURE" "$QUERY" "$SIMULATOR" "http://localhost:8082"; do
  for _ in $(seq 1 120); do
    curl -sf "$url/actuator/health" | grep -q '"UP"' && break
    sleep 2
  done
  curl -sf "$url/actuator/health" | grep -q '"UP"' || { echo "FAIL  $url is not healthy"; exit 1; }
done

echo "Run id $RUN"
check "new trade is accepted for processing (202)"            202 "$(post "$RUN-A" 1 B1 "$FUTURE")"
check "higher version is accepted for processing (202)"       202 "$(post "$RUN-A" 2 B1 "$FUTURE")"
check "same version is accepted for processing (202)"         202 "$(post "$RUN-A" 2 B2 "$FUTURE")"
check "lower version is accepted for processing (202)"        202 "$(post "$RUN-A" 1 B3 "$FUTURE")"
check "trade maturing today is accepted (202)"                202 "$(post "$RUN-B" 1 B1 "$(day +0)")"
check "past maturity is rejected at once (422)"               422 "$(post "$RUN-C" 1 B1 "$YESTERDAY")"
check "incomplete trade is refused (400)"                     400 "$(curl -s -o /dev/null -w '%{http_code}' \
  -X POST "$CAPTURE/api/v1/trades" -H 'Content-Type: application/json' -d '{"tradeId":"X"}')"

TRADES="$QUERY/api/v1/trades?tradeId=$RUN"
await "store holds every version, highest first"       '[.items[] | "\(.tradeId|split("-")[1])v\(.version)"] | join(" ")' "$TRADES" "Av2 Av1 Bv1"
await "same version replaced the stored record"        '.items[0].bookId'  "$TRADES" "B2"
await "lower version did not overwrite version 1"      '.items[1].bookId'  "$TRADES" "B1"
await "stored trades are not expired"                  '[.items[].expired] | any' "$TRADES" "false"
await "past-maturity trade is not in the store"        '.total' "$QUERY/api/v1/trades?tradeId=$RUN-C" "0"

REJ="$QUERY/api/v1/rejections?limit=500"
await "lower version is recorded as a rejection by the store" \
  "[.[] | select(.trade.tradeId==\"$RUN-A\")] | map(\"\(.reason)/\(.source)\") | join(\" \")" "$REJ" "LOWER_VERSION/PROCESSOR"
await "past maturity is recorded as a rejection by the capture API" \
  "[.[] | select(.trade.tradeId==\"$RUN-C\")] | map(\"\(.reason)/\(.source)\") | join(\" \")" "$REJ" "MATURITY_DATE_PASSED/CAPTURE"

echo "Simulator run: 500 trades with a mix of scenarios"
curl -sf -X POST "$SIMULATOR/api/v1/simulations" -H 'Content-Type: application/json' \
  -d '{"count":500,"ratePerSecond":500,"higherVersionPct":20,"sameVersionPct":10,"lowerVersionPct":10,"pastMaturityPct":10}' >/dev/null
await "simulator sent all 500 trades"  '"\(.sent)/\(.running)"' "$SIMULATOR/api/v1/simulations/current" "500/false"
check "simulator run finished without error" "null" "$(curl -sf "$SIMULATOR/api/v1/simulations/current" | jq -r '.error')"

check "dashboard is served" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$DASHBOARD/")"

if [ "$FAILED" -ne 0 ]; then
  echo "REGRESSION FAILED"
  exit 1
fi
echo "REGRESSION PASSED"
