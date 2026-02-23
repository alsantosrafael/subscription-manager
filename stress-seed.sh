#!/usr/bin/env bash
# =============================================================
# stress-seed.sh — Stress test combinado
#
# Roda DUAS fases em paralelo:
#
#   FASE 1 — Scheduler load
#     Chama POST /api/test/seed (bulk SQL) + trigger-sweep em loop.
#     Garante que o scheduler tenha sempre assinaturas vencidas
#     para processar e que o circuit breaker + backoff sejam exercitados.
#
#   FASE 2 — Jornadas HTTP reais (signup flow)
#     Simula usuários reais: POST /v1/users → POST /v1/subscriptions
#     → GET /v1/subscriptions/{id} → PATCH cancel (30% dos casos).
#     Exercita transações, idempotência, cache Redis e circuit breaker
#     no caminho síncrono (fora do scheduler).
#
# Uso:
#   ./stress-seed.sh [count] [cycles] [http_users]
#
#   count       — assinaturas por chamada de seed       (padrão: 20)
#   cycles      — ciclos seed+sweep                     (padrão: 5)
#   http_users  — jornadas HTTP concorrentes por ciclo  (padrão: 5)
#
# Exemplos:
#   ./stress-seed.sh                  # 5 ciclos × 20 subs + 5 jornadas HTTP
#   ./stress-seed.sh 50 10 10         # 10 ciclos × 50 subs + 10 jornadas HTTP
#
# Requer: curl, jq
# App deve estar rodando em localhost:8080
# =============================================================

set -euo pipefail

COUNT=${1:-20}
CYCLES=${2:-5}
HTTP_USERS=${3:-5}
BASE_URL="http://localhost:8080"

# HTTP journeys always use tok_test_success — the gateway approves and the sub becomes ACTIVE.
# tok_test_always_fail is exercised by the sweeper via seeded subs (billingAttempts=2), not here.
TOKENS=("tok_test_success")
PLANS=("BASICO" "PREMIUM" "FAMILIA")

echo "🔧 Stress test: ${CYCLES} ciclos × ${COUNT} subs (seed) + ${HTTP_USERS} jornadas HTTP/ciclo"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# ── Espera app estar pronta ──────────────────────────────────
echo "⏳ Aguardando app em ${BASE_URL}/actuator/health ..."
WAIT=0
MAX_WAIT=120
until curl -sf "${BASE_URL}/actuator/health" | grep -q '"status":"UP"' 2>/dev/null; do
  if [ "$WAIT" -ge "$MAX_WAIT" ]; then
    echo "❌ App não ficou pronta após ${MAX_WAIT}s."
    echo "   docker compose logs app --tail=30"
    exit 1
  fi
  sleep 3
  WAIT=$((WAIT + 3))
  echo "   ... ${WAIT}s"
done
echo "✅ App pronta!"
echo ""

# ── Aguarda CB recuperar de execuções anteriores ─────────────
# O circuit breaker é in-memory e pode estar OPEN de um run anterior.
# wait-duration-in-open-state=10s → 12s garante que está CLOSED antes de começar.
echo "⏱️  Aguardando 12s para o circuit breaker fechar (caso esteja aberto de um run anterior)..."
sleep 12

# ── Funções ──────────────────────────────────────────────────

# Gera um e-mail único usando timestamp + random para evitar conflito 409
# Usa $RANDOM duas vezes para garantir unicidade no macOS (sem %N)
random_email() {
  echo "user_$(date +%s)_${RANDOM}${RANDOM}@stress.test"
}

# Gera um CPF único de 11 dígitos (não validado — só precisa ser único)
random_doc() {
  printf "%05d%06d" $((RANDOM % 99999)) $((RANDOM % 999999))
}

# Jornada completa de um usuário:
#   1. Cria usuário
#   2. Cria assinatura (token aleatório)
#   3. Lê assinatura (valida cache Redis)
#   4. 30% chance de cancelar (testa fluxo de cancelamento)
run_signup_journey() {
  local idx=$1
  local token="${TOKENS[$((RANDOM % ${#TOKENS[@]}))]}"
  local plan="${PLANS[$((RANDOM % ${#PLANS[@]}))]}"
  local email
  email=$(random_email)
  local doc
  doc=$(random_doc)

  # 1. Criar usuário
  # curl -w "%{http_code}" appends status on the last line; we split on the last newline.
  # Using a temp file avoids the macOS `head -n -1` incompatibility.
  local user_http_status
  local user_body
  local _tmp_u
  _tmp_u=$(mktemp)
  user_http_status=$(curl -s -o "$_tmp_u" -w "%{http_code}" -X POST "${BASE_URL}/v1/users" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Stress User ${idx}\",\"document\":\"${doc}\",\"email\":\"${email}\"}" \
    2>/dev/null)
  user_body=$(cat "$_tmp_u"); rm -f "$_tmp_u"

  if [ "${user_http_status:-0}" -ge 400 ] 2>/dev/null; then
    local err_msg
    err_msg=$(echo "$user_body" | jq -r '.message // .error // .title // "erro desconhecido"' 2>/dev/null || echo "$user_body")
    echo "    [HTTP #${idx}] ❌ Usuário falhou HTTP ${user_http_status}: ${err_msg}"
    return
  fi

  local user_id
  user_id=$(echo "$user_body" | jq -r '.id // empty')
  if [ -z "$user_id" ]; then
    echo "    [HTTP #${idx}] ❌ userId não retornado (HTTP ${user_http_status}): ${user_body}"
    return
  fi

  # 2. Criar assinatura — captura corpo + status HTTP para distinguir erro de API de recusa do gateway
  local sub_http_status
  local sub_body
  local _tmp_s
  _tmp_s=$(mktemp)
  sub_http_status=$(curl -s -o "$_tmp_s" -w "%{http_code}" -X POST "${BASE_URL}/v1/subscriptions" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"${user_id}\",\"plan\":\"${plan}\",\"paymentToken\":\"${token}\"}" \
    2>/dev/null)
  sub_body=$(cat "$_tmp_s"); rm -f "$_tmp_s"
  local sub_resp="$sub_body"
  local sub_http_status_orig="$sub_http_status"

  if [ "${sub_http_status_orig:-0}" -ge 400 ] 2>/dev/null; then
    local err_msg
    err_msg=$(echo "$sub_resp" | jq -r '.message // .error // .title // "erro desconhecido"' 2>/dev/null || echo "$sub_resp")
    echo "    [HTTP #${idx}] ❌ Sub falhou HTTP ${sub_http_status_orig} (plan=${plan}, token=${token}): ${err_msg}"
    return
  fi

  local sub_id
  sub_id=$(echo "$sub_resp" | jq -r '.id // empty')
  if [ -z "$sub_id" ]; then
    echo "    [HTTP #${idx}] ⚠️  Sub não retornou id (plan=${plan}, token=${token}): ${sub_resp}"
    return
  fi

  # 3. Ler assinatura (aquece cache)
  curl -sf "${BASE_URL}/v1/subscriptions/${sub_id}" \
    -H "X-User-Id: ${user_id}" > /dev/null 2>/dev/null || true

  # 4. Cancelar com probabilidade 30%
  if [ $((RANDOM % 10)) -lt 3 ]; then
    curl -sf -X PATCH "${BASE_URL}/v1/subscriptions/${sub_id}/cancel" \
      -H "X-User-Id: ${user_id}" > /dev/null 2>/dev/null || true
    echo "    [HTTP #${idx}] ✅ ${plan}/${token} → criada + cancelada"
  else
    echo "    [HTTP #${idx}] ✅ ${plan}/${token} → criada (userId=${user_id})"
  fi
}

# ── Loop principal ───────────────────────────────────────────
for i in $(seq 1 "$CYCLES"); do
  echo "▶ Ciclo ${i}/${CYCLES}"

  # — Fase 1: jornadas HTTP reais PRIMEIRO (antes do sweep abrir o CB) —
  echo "  👤 Iniciando ${HTTP_USERS} jornadas HTTP em paralelo..."
  pids=()
  for j in $(seq 1 "$HTTP_USERS"); do
    run_signup_journey "$j" &
    pids+=($!)
  done
  for pid in "${pids[@]}"; do
    wait "$pid" 2>/dev/null || true
  done

  # — Fase 2: seed bulk + sweep (pode abrir o CB via always_fail) —
  SEED_RESULT=$(curl -sf -X POST "${BASE_URL}/api/test/seed?count=${COUNT}" \
    -H "Content-Type: application/json")
  echo "  🌱 Seed: $(echo "$SEED_RESULT" | jq -r '.message // .error // "ok"')"

  curl -sf -X POST "${BASE_URL}/v1/admin/billing/trigger-sweep" > /dev/null
  echo "  ⚙️  Sweep disparado"

  # Pausa para Kafka + BillingWorker processar + CB recuperar (waitDurationInOpenState=10s)
  sleep 15

  # — Snapshot de estado —
  STATE=$(curl -sf "${BASE_URL}/v1/admin/subscriptions" 2>/dev/null | \
    jq '[.[] | {status}] | group_by(.status) | map({status: .[0].status, count: length})' \
    2>/dev/null || echo "[]")
  echo "  📊 Estado atual: $(echo "$STATE" | jq -c .)"
  echo ""
done

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ Stress test concluído."
echo ""
echo "Estado final detalhado:"
curl -sf "${BASE_URL}/v1/admin/subscriptions" 2>/dev/null | \
  jq '[.[] | {id, status, billing_attempts, next_retry_at}]' \
  2>/dev/null || echo "(erro ao buscar estado final)"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🔍 Verificação pós-seed (cenários de negócio):"
VERIFY=$(curl -s "${BASE_URL}/v1/admin/verify" 2>/dev/null)
if [ -z "$VERIFY" ]; then
  echo "  ⚠️  Endpoint /v1/admin/verify não respondeu."
else
  echo "$VERIFY" | jq '
    "  📦 Assinaturas de seed : \(.seededSubscriptions) (exclui subs criadas via HTTP journey)",
    "  \(if .passed then "✅ PASSED" else "❌ FAILED" end) — todos os cenários dentro da tolerância",
    "  📋 Cenários:",
    "     • Ativas total (~80%)      : actual=\(.checks.active.actual) (renovadas=\(.checks.active.detail_clean_renewal), retry=\(.checks.active.detail_pending_retry)) expected≈\(.checks.active.expected_approx) — \(if .checks.active.passed then "✅" else "❌" end)",
    "     • Suspensas (SUSPENDED)    : actual=\(.checks.suspended.actual) expected≈\(.checks.suspended.expected_approx) — \(if .checks.suspended.passed then "✅" else "❌" end)",
    "     • Inativas  (INACTIVE)     : actual=\(.checks.becameInactive.actual) expected≈\(.checks.becameInactive.expected_approx) — \(if .checks.becameInactive.passed then "✅" else "❌" end)",
    "     • Outbox limpo             : pending=\(.checks.outboxClean.pending) — \(if .checks.outboxClean.passed then "✅" else "⚠️  aguarde ~5s e re-execute" end)",
    if .hint then "  💡 \(.hint)" else "" end
  ' 2>/dev/null || echo "$VERIFY" | jq .
fi

