#!/usr/bin/env bash
# =============================================================================
# smoke-test.sh — E2E smoke tests for the Subscription Manager
#
# Cenários cobertos:
#   1. Criar usuário + assinatura (tok_test_success) → ACTIVE
#   2. Cancelar assinatura → CANCELED, autoRenew=false
#   3. Seed em massa + sweep → renovação, suspensão, inativação automática
#   4. Verificação de negócio via /v1/admin/verify
#   5. Idempotência — duplicata rejeitada com 409
#
# Uso:
#   ./smoke-test.sh [base_url]
#   ./smoke-test.sh http://localhost:8080
#
# Requer: curl, jq
# =============================================================================

BASE_URL="${1:-http://localhost:8080}"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'
PASS=0; FAIL=0

pass()    { echo -e "${GREEN}  ✔  $1${RESET}"; ((PASS++)); }
fail()    { echo -e "${RED}  ✘  $1${RESET}"; ((FAIL++)); }
section() { echo -e "\n${CYAN}${BOLD}▶ $1${RESET}"; }
info()    { echo -e "${YELLOW}  ℹ  $1${RESET}"; }

post() { curl -s -w "\n%{http_code}" -X POST "${BASE_URL}$1" -H "Content-Type: application/json" -d "$2"; }
body_of()   { echo "$1" | sed '$d'; }
status_of() { echo "$1" | tail -n1; }

echo -e "\n${BOLD}Subscription Manager — Smoke Test Suite${RESET}"
echo -e "Target: ${BASE_URL}\n"

# =============================================================================
section "0. Health check"
# =============================================================================
RAW=$(curl -s -w "\n%{http_code}" "${BASE_URL}/actuator/health")
STATUS=$(status_of "$RAW")
if [[ "$STATUS" == "200" ]]; then
  pass "App is UP"
else
  fail "App is not responding (HTTP $STATUS). Aborting."
  exit 1
fi

# =============================================================================
section "1. Create user + subscription → ACTIVE"
# =============================================================================
TS=$(date +%s)
RAW=$(post "/v1/users" "{\"name\":\"Smoke Tester\",\"document\":\"${TS:0:11}\",\"email\":\"smoke_${TS}@test.com\"}")
STATUS=$(status_of "$RAW")
USER_ID=$(body_of "$RAW" | jq -r '.id // empty')

if [[ "$STATUS" == "201" && -n "$USER_ID" ]]; then
  pass "User created (id=$USER_ID)"
else
  fail "User creation failed (HTTP $STATUS)"; exit 1
fi

RAW=$(post "/v1/subscriptions" "{\"userId\":\"$USER_ID\",\"plan\":\"PREMIUM\",\"paymentToken\":\"tok_test_success\"}")
STATUS=$(status_of "$RAW"); SUB_BODY=$(body_of "$RAW")
SUB_ID=$(echo "$SUB_BODY" | jq -r '.id // empty')
SUB_STATUS=$(echo "$SUB_BODY" | jq -r '.status // empty')

if [[ "$STATUS" == "201" && "$SUB_STATUS" == "ACTIVE" ]]; then
  pass "Subscription created and ACTIVE (id=$SUB_ID)"
else
  fail "Subscription creation failed (HTTP $STATUS, status=$SUB_STATUS)"; exit 1
fi

# =============================================================================
section "2. GET subscription — cache warm-up"
# =============================================================================
RAW=$(curl -s -w "\n%{http_code}" "${BASE_URL}/v1/subscriptions/${SUB_ID}" -H "X-User-Id: ${USER_ID}")
STATUS=$(status_of "$RAW")
CACHED_STATUS=$(body_of "$RAW" | jq -r '.status // empty')

if [[ "$STATUS" == "200" && "$CACHED_STATUS" == "ACTIVE" ]]; then
  pass "GET subscription returned ACTIVE (Redis or DB)"
else
  fail "GET subscription failed (HTTP $STATUS, status=$CACHED_STATUS)"
fi

# =============================================================================
section "3. Cancel subscription → CANCELED, autoRenew=false"
# =============================================================================
RAW=$(curl -s -w "\n%{http_code}" -X PATCH "${BASE_URL}/v1/subscriptions/${SUB_ID}/cancel" \
  -H "X-User-Id: ${USER_ID}")
STATUS=$(status_of "$RAW")
[[ "$STATUS" == "204" ]] && pass "Cancel returned 204" || fail "Cancel failed (HTTP $STATUS)"

RAW=$(curl -s -w "\n%{http_code}" "${BASE_URL}/v1/subscriptions/${SUB_ID}" -H "X-User-Id: ${USER_ID}")
BODY=$(body_of "$RAW")
CANCEL_STATUS=$(echo "$BODY" | jq -r '.status // empty')
AUTO_RENEW=$(echo "$BODY"   | jq -r '.autoRenew // empty')

if [[ "$CANCEL_STATUS" == "CANCELED" && "$AUTO_RENEW" == "false" ]]; then
  pass "Subscription is CANCELED with autoRenew=false"
else
  fail "Expected CANCELED/autoRenew=false, got status=$CANCEL_STATUS autoRenew=$AUTO_RENEW"
fi

# =============================================================================
section "4. Seed + sweep → renovação, suspensão, inativação"
# =============================================================================
info "Seeding 20 subscriptions (3 profiles: success 80%, always_fail 10%, canceled 10%)..."
RAW=$(post "/api/test/seed?count=20" "")
STATUS=$(status_of "$RAW"); TOTAL=$(body_of "$RAW" | jq -r '.totalSeeded // empty')

if [[ "$STATUS" == "200" && "$TOTAL" == "20" ]]; then
  pass "Seed OK — $TOTAL subscriptions inserted"
else
  fail "Seed failed (HTTP $STATUS, totalSeeded=$TOTAL)"; exit 1
fi

info "Triggering sweep..."
RAW=$(post "/v1/admin/billing/trigger-sweep" "")
STATUS=$(status_of "$RAW")
[[ "$STATUS" == "200" || "$STATUS" == "202" ]] \
  && pass "Sweep triggered (HTTP $STATUS)" \
  || fail "Sweep trigger failed (HTTP $STATUS)"

info "Waiting 10s for Kafka to process results..."
sleep 10

# =============================================================================
section "5. Business verification — /v1/admin/verify"
# =============================================================================
RAW=$(curl -s -w "\n%{http_code}" "${BASE_URL}/v1/admin/verify")
STATUS=$(status_of "$RAW"); BODY=$(body_of "$RAW")
PASSED=$(echo "$BODY" | jq -r '.passed // false')

if [[ "$STATUS" == "200" && "$PASSED" == "true" ]]; then
  pass "Business verification PASSED"
  echo "$BODY" | jq '{
    seededSubscriptions,
    checks: {
      active:         {actual: .checks.active.actual,         expected: .checks.active.expected_approx,         passed: .checks.active.passed},
      suspended:      {actual: .checks.suspended.actual,      expected: .checks.suspended.expected_approx,      passed: .checks.suspended.passed},
      becameInactive: {actual: .checks.becameInactive.actual, expected: .checks.becameInactive.expected_approx, passed: .checks.becameInactive.passed},
      outboxClean:    {pending: .checks.outboxClean.pending,  passed: .checks.outboxClean.passed}
    }
  }' 2>/dev/null || echo "$BODY"
else
  fail "Business verification FAILED (HTTP $STATUS, passed=$PASSED)"
  echo "$BODY" | jq '{passed, hint}' 2>/dev/null || echo "$BODY"
fi

# =============================================================================
section "6. Idempotency — duplicate active subscription rejected with 409"
# =============================================================================
TS2=$(date +%s)$RANDOM
RAW=$(post "/v1/users" "{\"name\":\"Idempotency Tester\",\"document\":\"${TS2:0:11}\",\"email\":\"idem_${TS2}@test.com\"}")
IDEM_USER=$(body_of "$RAW" | jq -r '.id // empty')
post "/v1/subscriptions" "{\"userId\":\"$IDEM_USER\",\"plan\":\"BASICO\",\"paymentToken\":\"tok_test_success\"}" > /dev/null

RAW=$(post "/v1/subscriptions" "{\"userId\":\"$IDEM_USER\",\"plan\":\"BASICO\",\"paymentToken\":\"tok_test_success\"}")
STATUS=$(status_of "$RAW")
[[ "$STATUS" == "409" ]] \
  && pass "Duplicate active subscription correctly rejected with 409" \
  || fail "Expected 409 for duplicate subscription, got $STATUS"

# =============================================================================
echo -e "\n${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "${BOLD}Results: ${GREEN}${PASS} passed${RESET}, ${RED}${FAIL} failed${RESET} / $((PASS + FAIL)) total${RESET}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}\n"

[[ $FAIL -eq 0 ]] && exit 0 || exit 1

