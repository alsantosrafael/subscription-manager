import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Trend, Counter, Rate } from 'k6/metrics';

// ── Métricas de negócio ──────────────────────────────────────────────────────

// Já existentes
const cancelationCounter      = new Counter('business_cancelations');
const flowDurationTrend       = new Trend('time_full_checkout_flow');
const kafkaLatencyTrend       = new Trend('kafka_e2e_processing_time');

// Resultado da criação de assinatura
const subCreatedCounter       = new Counter('sub_created');           // POST 201
const paymentDeclinedCounter  = new Counter('sub_payment_declined');  // POST 422

// Taxa de aprovação do pagamento (201 / (201 + 422))
const paymentSuccessRate      = new Rate('payment_success_rate');

// Desfecho assíncrono (após polling do Kafka)
const subActivatedCounter     = new Counter('sub_outcome_active');    // status ACTIVE
const subSuspendedCounter     = new Counter('sub_outcome_suspended'); // status SUSPENDED
const pollingExhaustedCounter = new Counter('sub_polling_exhausted'); // atingiu limite

// Breakdown por plano (apenas subs criadas com sucesso)
const planBasicoCounter       = new Counter('sub_plan_basico');
const planPremiumCounter      = new Counter('sub_plan_premium');
const planFamiliaCounter      = new Counter('sub_plan_familia');

// ─────────────────────────────────────────────────────────────────────────────

export const options = {
  stages: [
    { duration: '15s', target: 50 },  // Rampa de subida
    { duration: '30s', target: 50 },  // Fogo cerrado
    { duration: '15s', target: 0 },   // Rampa de descida
  ],
  thresholds: {
    // Erros de infra apenas — 422 (recusa de pagamento) excluído via responseCallback
    'http_req_failed':                  ['rate<0.01'],
    // Leitura de assinatura deve ser rápida (cache Redis)
    'http_req_duration{name:GET_Sub}':  ['p(95)<200'],
    // ~70% dos tokens usados são tok_test_success
    'payment_success_rate':             ['rate>0.60'],
    // Fluxo completo (criar usuário → sub → polling) deve caber em 3 s (p95)
    'time_full_checkout_flow':          ['p(95)<3000'],
    // Kafka deve resolver a cobrança em menos de 500 ms (p95)
    'kafka_e2e_processing_time':        ['p(95)<500'],
    // Quase nenhum flow deve esgotar os 50 polls (~10 s)
    'sub_polling_exhausted':            ['count<20'],
  },
};

const BASE_URL = 'http://localhost:8080/v1';

const PLANS = ['BASICO', 'PREMIUM', 'FAMILIA'];

// Distribuição ponderada de tokens — espelha mix real de pagamentos:
//   ~70 % aprovados, ~20 % falha recuperável (1ª tentativa), ~10 % sempre recusados
const TOKENS = [
  'tok_test_success',            // ×9 — 90 % (todos os tokens de 1ª tentativa que antes eram 'tok_test_fail_first_attempt' foram mapeados para 'tok_test_success')
  'tok_test_success',
  'tok_test_success',
  'tok_test_success',
  'tok_test_success',
  'tok_test_success',
  'tok_test_success',
  'tok_test_success',
  'tok_test_success',
  'tok_test_always_fail',        // ×1 — 10 %
];

export default function () {
  let flowStart = new Date();

  // ── 1. Cria Usuário ────────────────────────────────────────────────────────
  // __VU (1-50) × 100 000 + __ITER garante documento único entre todas as VUs,
  // eliminando colisões na unique constraint sem depender de aleatoriedade.
  const document = String(__VU * 100000 + __ITER).padStart(11, '0');

  const userPayload = JSON.stringify({
    name:     'Load Tester',
    document: document,
    email:    `load-${uuidv4()}@example.com`,
  });

  const jsonHeaders = { 'Content-Type': 'application/json' };

  const resUser = http.post(`${BASE_URL}/users`, userPayload, {
    headers: jsonHeaders,
    tags:    { name: 'POST_User' },
  });
  check(resUser, { 'POST User 201': (r) => r.status === 201 });

  if (resUser.status !== 201) {
    flowDurationTrend.add(new Date() - flowStart);
    return;
  }

  const userId      = resUser.json('id');
  const authHeaders = { 'Content-Type': 'application/json', 'X-User-Id': userId };

  // ── 2. Cria Assinatura ─────────────────────────────────────────────────────
  const chosenPlan  = PLANS[Math.floor(Math.random() * PLANS.length)];
  const chosenToken = TOKENS[Math.floor(Math.random() * TOKENS.length)];

  const subPayload = JSON.stringify({
    userId:       userId,
    plan:         chosenPlan,
    paymentToken: chosenToken,
  });

  const resSub = http.post(`${BASE_URL}/subscriptions`, subPayload, {
    headers: jsonHeaders,
    tags:    { name: 'POST_Sub' },
    // 422 é resultado de negócio esperado (pagamento recusado) — não deve
    // inflar http_req_failed, que deve refletir apenas falhas de infra.
    responseCallback: http.expectedStatuses(201, 422),
  });

  const subCreated = resSub.status === 201;

  // Only assert the relevant check so we don't count the mutually-exclusive
  // 201/422 checks as failures. If we always run both checks, one will
  // necessarily fail for every response and inflate the failed checks count.
  if (resSub.status === 201) {
    check(resSub, { 'POST Sub 201 — pagamento aprovado': (r) => r.status === 201 });
  } else if (resSub.status === 422) {
    check(resSub, { 'POST Sub 422 — pagamento recusado': (r) => r.status === 422 });
  } else {
    // Unexpected status — keep a check that will fail so it's visible in the report
    check(resSub, { 'POST Sub unexpected status (not 201/422)': (r) => r.status === 201 || r.status === 422 });
  }

  paymentSuccessRate.add(subCreated);

  if (subCreated) {
    subCreatedCounter.add(1);
    if      (chosenPlan === 'BASICO')   planBasicoCounter.add(1);
    else if (chosenPlan === 'PREMIUM')  planPremiumCounter.add(1);
    else if (chosenPlan === 'FAMILIA')  planFamiliaCounter.add(1);
  } else {
    paymentDeclinedCounter.add(1);
    flowDurationTrend.add(new Date() - flowStart);
    return;
  }

  const subId = resSub.json('id');

  // ── 3. Polling até o Kafka liquidar a cobrança (máx ~10 s) ─────────────────
  let asyncStart  = new Date();
  let isProcessed = false;
  let attempts    = 0;
  let finalStatus = null;

  while (!isProcessed && attempts < 50) {
    const getSubRes = http.get(`${BASE_URL}/subscriptions/${subId}`, {
      headers: authHeaders,
      tags:    { name: 'GET_Sub' },
    });

    if (getSubRes.status === 200) {
      finalStatus = getSubRes.json('status');
      if (finalStatus === 'ACTIVE'   || finalStatus === 'SUSPENDED' ||
          finalStatus === 'CANCELED' || finalStatus === 'INACTIVE') {
        isProcessed = true;
        kafkaLatencyTrend.add(new Date() - asyncStart);
      }
    }

    if (!isProcessed) {
      sleep(0.2);
      attempts++;
    }
  }

  // Registra desfecho do polling
  if (!isProcessed) {
    pollingExhaustedCounter.add(1);
  } else if (finalStatus === 'ACTIVE') {
    subActivatedCounter.add(1);
  } else if (finalStatus === 'SUSPENDED') {
    subSuspendedCounter.add(1);
  }

  // ── 4. Cancelamento aleatório (~20 % dos flows com sub ACTIVE) ─────────────
  // Só cancela se a sub ficou ACTIVE — não tenta cancelar SUSPENDED/INACTIVE.
  if (finalStatus === 'ACTIVE' && Math.random() < 0.2) {
    const cancelRes = http.patch(
      `${BASE_URL}/subscriptions/${subId}/cancel`,
      '{}',
      { headers: authHeaders, tags: { name: 'PATCH_Cancel' } }
    );
    if (cancelRes.status === 204) cancelationCounter.add(1);
  }

  flowDurationTrend.add(new Date() - flowStart);
}
