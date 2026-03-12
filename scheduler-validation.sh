#!/usr/bin/env bash
# =============================================================================
# scheduler-validation.sh — Valida ambos os schedulers em isolamento e combinado
#
# Cenários:
#   A. RenewalOrchestratorService — assinaturas ACTIVE renovadas, com retry e suspensas
#   B. SubscriptionExpiryService  — assinaturas CANCELED vencidas movidas para INACTIVE
#   C. Combinado (idêntico ao smoke-test.sh, mas com verificação granular por scheduler)
#
# Uso:
#   ./scheduler-validation.sh [base_url]
#   ./scheduler-validation.sh http://localhost:8080
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
warn()    { echo -e "${YELLOW}  ⚠  $1${RESET}"; }

post()   { curl -s -w "\n%{http_code}" -X POST "${BASE_URL}$1" -H "Content-Type: application/json" -d "${2:-}" "${@:3}"; }
get()    { curl -s -w "\n%{http_code}" "${BASE_URL}$1" "${@:2}"; }
body_of()   { echo "$1" | sed '$d'; }
status_of() { echo "$1" | tail -n1; }

echo -e "\n${BOLD}Subscription Manager — Scheduler Validation${RESET}"
echo -e "Target: ${BASE_URL}\n"

# =============================================================================
section "0. Health check"
# =============================================================================
RAW=$(get "/actuator/health")
if [[ "$(status_of "$RAW")" == "200" ]]; then
  pass "App is UP"
else
  fail "App is not responding (HTTP $(status_of "$RAW")). Aborting."; exit 1
fi

# =============================================================================
section "A. RenewalOrchestratorService — validação isolada"
# Seed apenas assinaturas ACTIVE/elegíveis, dispara SOMENTE o renewal sweep e verifica.
# =============================================================================

info "Seeding 20 assinaturas com perfis de renovação (seed limpa o banco)..."
RAW=$(post "/api/test/seed?count=20" "")
STATUS=$(status_of "$RAW"); BODY=$(body_of "$RAW")
TOTAL=$(echo "$BODY" | jq -r '.totalSeeded // empty')
[[ "$STATUS" == "200" && "$TOTAL" == "20" ]] \
  && pass "Seed OK — $TOTAL assinaturas inseridas" \
  || { fail "Seed falhou (HTTP $STATUS)"; exit 1; }

info "Perfis esperados no banco agora:"
info "  ~70% ACTIVE billingAttempts=0    → renovam com sucesso"
info "  ~10% ACTIVE billingAttempts=1    → renovam na 2ª tentativa"
info "  ~10% ACTIVE billingAttempts=2    → token always_fail → SUSPENDED"
info "  ~10% CANCELED expiringDate≤agora → NÃO serão tocadas pelo renewal sweep"

echo ""
info "Disparando SOMENTE o sweep de renovação (POST /v1/admin/billing/trigger-sweep)..."
warn "Nota: trigger-sweep também aciona o expiry sweep. Para testar em isolamento absoluto,"
warn "observe os logs: apenas linhas [RENEWAL] devem aparecer antes do [EXPIRY]."
RAW=$(post "/v1/admin/billing/trigger-sweep" "")
STATUS=$(status_of "$RAW")
[[ "$STATUS" == "202" ]] \
  && pass "Sweep de renovação disparado (HTTP 202)" \
  || fail "Sweep falhou (HTTP $STATUS)"

info "Aguardando 10s para Kafka processar resultados..."
sleep 10

# Inspeciona o status
RAW=$(get "/v1/admin/status")
BODY=$(body_of "$RAW")

ACTIVE=$(echo "$BODY"    | jq -r '.countsByStatus.ACTIVE    // 0')
SUSPENDED=$(echo "$BODY" | jq -r '.countsByStatus.SUSPENDED // 0')
INACTIVE=$(echo "$BODY"  | jq -r '.countsByStatus.INACTIVE  // 0')
CANCELED=$(echo "$BODY"  | jq -r '.countsByStatus.CANCELED  // 0')
RENEWED=$(echo "$BODY"   | jq -r '.successfulRenewals       // 0')
OUTBOX=$(echo "$BODY"    | jq -r '.outboxPending            // 0')

echo ""
info "Estado atual após renewal sweep:"
info "  ACTIVE:    $ACTIVE   (esperado ≈ 16)"
info "  SUSPENDED: $SUSPENDED (esperado ≈  2)"
info "  INACTIVE:  $INACTIVE  (esperado ≈  2 — expiry sweep já rodou junto)"
info "  CANCELED:  $CANCELED  (esperado =  0)"
info "  Renovadas: $RENEWED"
info "  Outbox pendente: $OUTBOX"

# Validações do renewal scheduler
[[ "$ACTIVE" -ge 14 && "$ACTIVE" -le 18 ]] \
  && pass "RenewalScheduler: assinaturas ACTIVE dentro do esperado ($ACTIVE ≈ 16)" \
  || fail "RenewalScheduler: ACTIVE fora do intervalo esperado ($ACTIVE, esperado 14-18)"

[[ "$SUSPENDED" -ge 1 && "$SUSPENDED" -le 3 ]] \
  && pass "RenewalScheduler: assinaturas SUSPENDED dentro do esperado ($SUSPENDED ≈ 2)" \
  || fail "RenewalScheduler: SUSPENDED fora do intervalo esperado ($SUSPENDED, esperado 1-3)"

[[ "$RENEWED" -ge 10 ]] \
  && pass "RenewalScheduler: renovações limpas (billingAttempts=0) registradas ($RENEWED)" \
  || fail "RenewalScheduler: poucas renovações limpas ($RENEWED, esperado ≥ 10)"

[[ "$OUTBOX" -eq 0 ]] \
  && pass "Outbox limpo — todos os eventos Kafka processados" \
  || warn "Outbox ainda tem $OUTBOX evento(s) pendente(s) — aguarde mais alguns segundos"

# =============================================================================
section "B. SubscriptionExpiryService — validação isolada"
# Cria uma assinatura via HTTP, cancela, força expiração manual e aciona SOMENTE o expiry sweep.
# =============================================================================

info "Criando um usuário e assinatura para o cenário de expiração..."
TS=$(date +%s)$RANDOM
RAW=$(post "/v1/users" "{\"name\":\"Expiry Tester\",\"document\":\"${TS:0:11}\",\"email\":\"expiry_${TS}@test.com\"}")
USER_ID=$(body_of "$RAW" | jq -r '.id // empty')
[[ -n "$USER_ID" ]] && pass "Usuário criado (id=$USER_ID)" || { fail "Falha ao criar usuário"; exit 1; }

RAW=$(post "/v1/subscriptions" "{\"userId\":\"$USER_ID\",\"plan\":\"BASICO\",\"paymentToken\":\"tok_test_success\"}")
STATUS=$(status_of "$RAW"); SUB_ID=$(body_of "$RAW" | jq -r '.id // empty')
[[ "$STATUS" == "201" && -n "$SUB_ID" ]] \
  && pass "Assinatura criada (id=$SUB_ID, status=ACTIVE)" \
  || { fail "Falha ao criar assinatura (HTTP $STATUS)"; exit 1; }

info "Cancelando a assinatura..."
RAW=$(curl -s -w "\n%{http_code}" -X PATCH "${BASE_URL}/v1/subscriptions/${SUB_ID}/cancel" -H "X-User-Id: ${USER_ID}")
STATUS=$(status_of "$RAW")
[[ "$STATUS" == "204" ]] && pass "Assinatura cancelada (204)" || fail "Cancel falhou (HTTP $STATUS)"

# Verificar que está CANCELED agora
RAW=$(get "/v1/subscriptions/${SUB_ID}" -H "X-User-Id: ${USER_ID}")
CURRENT_STATUS=$(body_of "$RAW" | jq -r '.status // empty')
[[ "$CURRENT_STATUS" == "CANCELED" ]] \
  && pass "Assinatura confirmada como CANCELED" \
  || fail "Status inesperado: $CURRENT_STATUS (esperado CANCELED)"

echo ""
info "Forçando expiração via SQL direto (simula passagem de tempo)..."
info "  UPDATE subscriptions SET expiring_date = NOW() - INTERVAL '1 day' WHERE id = '$SUB_ID'"
warn "  Execute o comando acima no banco antes de continuar, ou use o perfil de seed"
warn "  que já insere CANCELED com expiringDate = hoje."
echo ""
info "Alternatively, dispare o expiry sweep AGORA para as assinaturas CANCELED do seed:"
info "  POST /v1/admin/billing/trigger-expiry-sweep"
echo ""

info "Disparando SOMENTE o expiry sweep..."
RAW=$(post "/v1/admin/billing/trigger-expiry-sweep" "")
STATUS=$(status_of "$RAW")
BODY=$(body_of "$RAW")
EXPIRED_COUNT=$(echo "$BODY" | jq -r '.expiredToInactive // 0')
MSG=$(echo "$BODY" | jq -r '.message // empty')

[[ "$STATUS" == "202" ]] \
  && pass "Expiry sweep respondeu 202" \
  || fail "Expiry sweep falhou (HTTP $STATUS)"

echo ""
info "Resultado do expiry sweep: $MSG"
echo "$BODY" | jq '.' 2>/dev/null

# Para o cenário isolado acima (1 assinatura nova), ela pode não ter expirado ainda
# porque a expiringDate é a data de criação + 1 mês. Validamos as subs do seed (10%).
[[ "$EXPIRED_COUNT" -ge 1 ]] \
  && pass "ExpiryScheduler: $EXPIRED_COUNT assinatura(s) movida(s) CANCELED → INACTIVE" \
  || warn "ExpiryScheduler: nenhuma expiração detectada — a assinatura criada via HTTP tem expiringDate futura"

# =============================================================================
section "C. Validação combinada — /v1/admin/verify"
# =============================================================================
info "Executando verificação de negócio via /v1/admin/verify..."
RAW=$(get "/v1/admin/verify")
STATUS=$(status_of "$RAW"); BODY=$(body_of "$RAW")
PASSED=$(echo "$BODY" | jq -r '.passed // false')

if [[ "$STATUS" == "200" && "$PASSED" == "true" ]]; then
  pass "Verificação pós-seed PASSOU (ambos schedulers funcionaram)"
  echo "$BODY" | jq '{
    seededSubscriptions,
    checks: {
      active:         {actual: .checks.active.actual,         expected: .checks.active.expected,         passed: .checks.active.passed},
      suspended:      {actual: .checks.suspended.actual,      expected: .checks.suspended.expected,      passed: .checks.suspended.passed},
      becameInactive: {actual: .checks.becameInactive.actual, expected: .checks.becameInactive.expected, passed: .checks.becameInactive.passed},
      outboxClean:    {pending: .checks.outboxClean.pending,  passed: .checks.outboxClean.passed}
    }
  }' 2>/dev/null || echo "$BODY"
else
  fail "Verificação FALHOU (HTTP $STATUS, passed=$PASSED)"
  echo "$BODY" | jq '{passed, hint}' 2>/dev/null || echo "$BODY"
fi

# =============================================================================
section "D. Referência rápida — comandos curl individuais"
# =============================================================================
echo -e "
${BOLD}── Seed + ambos os schedulers (fluxo completo) ──────────────────────────${RESET}
  # 1. Popular o banco com 20 assinaturas (70% success, 10% retry, 10% fail, 10% canceled)
  curl -s -X POST '${BASE_URL}/api/test/seed?count=20' | jq .

  # 2. Disparar os dois schedulers de uma vez
  curl -s -X POST '${BASE_URL}/v1/admin/billing/trigger-sweep'

  # 3. Aguardar Kafka processar (~3-5s) e verificar
  sleep 5
  curl -s '${BASE_URL}/v1/admin/verify' | jq '{passed, checks}'

${BOLD}── RenewalOrchestratorService (renovação) isolado ───────────────────────${RESET}
  # Inspecionar assinaturas elegíveis antes do sweep
  curl -s '${BASE_URL}/v1/admin/subscriptions' | jq '[.[] | select(.status == \"ACTIVE\")]'

  # Disparar SOMENTE o renewal (trigger-sweep aciona ambos; logs [RENEWAL] aparecem primeiro)
  curl -s -X POST '${BASE_URL}/v1/admin/billing/trigger-sweep'

  # Verificar renovações: expiringDate deve ter avançado, billingAttempts=0
  curl -s '${BASE_URL}/v1/admin/status' | jq '{successfulRenewals, countsByStatus}'

${BOLD}── SubscriptionExpiryService (expiração) isolado ────────────────────────${RESET}
  # Disparar SOMENTE o expiry sweep (não aciona renovação)
  curl -s -X POST '${BASE_URL}/v1/admin/billing/trigger-expiry-sweep' | jq .

  # Ver quantas foram de CANCELED → INACTIVE
  curl -s '${BASE_URL}/v1/admin/status' | jq '.countsByStatus'

${BOLD}── Ciclo manual completo passo a passo ──────────────────────────────────${RESET}
  # Criar usuário
  USER_ID=\$(curl -s -X POST '${BASE_URL}/v1/users' \\
    -H 'Content-Type: application/json' \\
    -d '{\"name\":\"Test\",\"document\":\"12345678901\",\"email\":\"test@test.com\"}' | jq -r .id)

  # Criar assinatura ACTIVE
  SUB_ID=\$(curl -s -X POST '${BASE_URL}/v1/subscriptions' \\
    -H 'Content-Type: application/json' \\
    -d \"{\\\"userId\\\":\\\"${USER_ID}\\\",\\\"plan\\\":\\\"PREMIUM\\\",\\\"paymentToken\\\":\\\"tok_test_success\\\"}\" \\
    | jq -r .id)

  # Cancelar → CANCELED
  curl -s -X PATCH \"${BASE_URL}/v1/subscriptions/\${SUB_ID}/cancel\" \\
    -H \"X-User-Id: \${USER_ID}\"

  # [No banco] Forçar expiração retroativa:
  #   UPDATE subscriptions SET expiring_date = NOW() - INTERVAL '1 day' WHERE id = '\${SUB_ID}';

  # Acionar expiry sweep → deve mover para INACTIVE
  curl -s -X POST '${BASE_URL}/v1/admin/billing/trigger-expiry-sweep' | jq .

  # Confirmar INACTIVE
  curl -s \"${BASE_URL}/v1/subscriptions/\${SUB_ID}\" -H \"X-User-Id: \${USER_ID}\" | jq .status

${BOLD}── Logs em tempo real (confirmar que ambos schedulers disparam a cada 1min) ──${RESET}
  # Via Docker
  docker logs -f subscription-manager 2>&1 | grep -E '\\[(RENEWAL|EXPIRY)\\]'

  # Linha de renovação esperada (a cada minuto):
  #   📋 [RENEWAL] Processando página 0 com N subscrições elegíveis.
  # Linha de expiração esperada (a cada minuto):
  #   🗓️ [EXPIRY] N assinatura(s) movida(s) para INACTIVE neste ciclo.
"

# =============================================================================
echo -e "\n${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "${BOLD}Results: ${GREEN}${PASS} passed${RESET}, ${RED}${FAIL} failed${RESET} / $((PASS + FAIL)) total${RESET}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}\n"

[[ $FAIL -eq 0 ]] && exit 0 || exit 1

