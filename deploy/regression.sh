#!/usr/bin/env bash
# End-to-end regression against a running system (docker compose up).
# Drives the capture API and checks the trade store through the query API.
# Requires: curl, jq. Dates are relative to today, so the script never goes stale.
set -euo pipefail

CAPTURE=${CAPTURE_URL:-http://localhost:8081}
QUERY=${QUERY_URL:-http://localhost:8083}
SIMULATOR=${SIMULATOR_URL:-http://localhost:8084}
DASHBOARD=${DASHBOARD_URL:-http://localhost:8080}
USER_NAME=${TRADE_SECURITY_USERNAME:-admin}
PASSWORD=${TRADE_SECURITY_PASSWORD:-admin@2026!}
AUTH="$USER_NAME:$PASSWORD"
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
  curl -s -u "$AUTH" -o /dev/null -w '%{http_code}' -X POST "$CAPTURE/api/v1/trades" -H 'Content-Type: application/json' \
    -d "{\"tradeId\":\"$1\",\"version\":$2,\"counterPartyId\":\"CP-1\",\"bookId\":\"$3\",\"maturityDate\":\"$4\"}"
}

await() { # await <description> <jq-filter> <url> <expected>; polls for up to 60 s
  local actual=""
  for _ in $(seq 1 60); do
    actual=$(curl -sf -u "$AUTH" "$3" | jq -r "$2" 2>/dev/null || true)
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
check "incomplete trade is refused (400)"                     400 "$(curl -s -u "$AUTH" -o /dev/null -w '%{http_code}' \
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
curl -sf -u "$AUTH" -X POST "$SIMULATOR/api/v1/simulations" -H 'Content-Type: application/json' \
  -d '{"count":500,"ratePerSecond":500,"higherVersionPct":20,"sameVersionPct":10,"lowerVersionPct":10,"pastMaturityPct":10}' >/dev/null
await "simulator sent all 500 trades"  '"\(.sent)/\(.running)"' "$SIMULATOR/api/v1/simulations/current" "500/false"
check "simulator run finished without error" "null" "$(curl -sf -u "$AUTH" "$SIMULATOR/api/v1/simulations/current" | jq -r '.error')"

code() { # code <curl args...>  ->  HTTP status
  curl -s -o /dev/null -w '%{http_code}' "$@"
}
echo "Authentication"
check "capture refuses a call without credentials (401)"   401 "$(code -X POST "$CAPTURE/api/v1/trades" -H 'Content-Type: application/json' -d '{}')"
check "capture refuses a wrong password (401)"             401 "$(code -u "$USER_NAME:wrong" -X POST "$CAPTURE/api/v1/trades" -H 'Content-Type: application/json' -d '{}')"
check "query refuses a call without credentials (401)"     401 "$(code "$QUERY/api/v1/trades")"
check "simulator refuses a call without credentials (401)" 401 "$(code "$SIMULATOR/api/v1/simulations/current")"
check "processor metrics need credentials (401)"           401 "$(code "http://localhost:8082/actuator/metrics")"
check "dashboard asks for a login (401)"                   401 "$(code "$DASHBOARD/")"
check "dashboard refuses a wrong password (401)"           401 "$(code -u "$USER_NAME:wrong" "$DASHBOARD/")"
check "dashboard is served after login (200)"              200 "$(code -u "$AUTH" "$DASHBOARD/")"
check "dashboard forwards the login to the query API"      200 "$(code -u "$AUTH" "$DASHBOARD/query/api/v1/summary")"
check "dashboard forwards the login to the simulator API"  200 "$(code -u "$AUTH" "$DASHBOARD/simulator/api/v1/simulations/current")"
check "dashboard API path refuses a call without login"    401 "$(code "$DASHBOARD/query/api/v1/summary")"

echo "Hardening"
check "dashboard sends a content security policy"        1 "$(curl -sI -u "$AUTH" "$DASHBOARD/" | grep -ic '^content-security-policy:')"
check "dashboard does not reveal its server version"     0 "$(curl -sI -u "$AUTH" "$DASHBOARD/" | grep -i '^server:' | grep -c '[0-9]\.[0-9]')"
check "oversized request body is refused (413)"          413 "$(head -c 3000000 /dev/zero | tr '\0' 'x' | code -u "$AUTH" -X POST "$CAPTURE/api/v1/trades" -H 'Content-Type: application/json' --data-binary @-)"
if command -v docker >/dev/null 2>&1; then
  COMPOSE="docker compose -f $(cd "$(dirname "$0")" && pwd)/docker-compose.yml"
  if [ -n "$($COMPOSE ps -q postgres 2>/dev/null)" ]; then
    RO_PASSWORD=${TRADE_RO_PASSWORD:-trade_ro}
    DASHBOARD_UID=$($COMPOSE exec -T trade-dashboard-ui id -u | tr -d '\r')
    check "dashboard server does not run as root"          false "$([ "$DASHBOARD_UID" = 0 ] && echo true || echo false)"
    check "read-only database account can read trades"     0 "$($COMPOSE exec -T -e PGPASSWORD="$RO_PASSWORD" postgres psql -U trade_ro -d tradestore -Atc 'SELECT 1 FROM trade LIMIT 1' >/dev/null 2>&1; echo $?)"
    # Capture the output first: with pipefail, grep -q closing the pipe early would look like a failure.
    DELETE_OUTPUT=$($COMPOSE exec -T -e PGPASSWORD="$RO_PASSWORD" postgres psql -U trade_ro -d tradestore -c 'DELETE FROM trade WHERE false' 2>&1 || true)
    check "read-only database account cannot change trades" denied "$(printf '%s' "$DELETE_OUTPUT" | grep -qi 'permission denied' && echo denied || echo allowed)"
  fi
fi

if [ "$FAILED" -ne 0 ]; then
  echo "REGRESSION FAILED"
  exit 1
fi
echo "REGRESSION PASSED"
