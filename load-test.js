import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Trend, Counter } from 'k6/metrics';

const cancelationCounter = new Counter('business_cancelations');
const flowDurationTrend = new Trend('time_full_checkout_flow');
const kafkaLatencyTrend = new Trend('kafka_e2e_processing_time'); // Nova métrica!

export const options = {
  stages: [
    { duration: '15s', target: 50 },  // Rampa de subida
    { duration: '30s', target: 50 },  // Fogo cerrado
    { duration: '15s', target: 0 },   // Rampa de descida
  ],
  thresholds: {
    'http_req_failed': ['rate<0.05'],
    'http_req_duration{name:GET_Sub}': ['p(95)<100'],
  },
};

const BASE_URL = 'http://localhost:8080/v1';
const TOKENS = [
  'tok_test_success',
  'tok_test_success',
  'tok_test_fail_first_attempt',
  'tok_test_always_fail'
];

export default function () {
  let flowStart = new Date();
  const defaultHeaders = { 'Content-Type': 'application/json' };

  // 1. Cria Usuário
  const userPayload = JSON.stringify({
    name: 'Load Tester',
    document: `000.${Math.floor(Math.random() * 999)}.${Math.floor(Math.random() * 999)}-00`,
    email: `load-${uuidv4()}@example.com`,
  });

  let resUser = http.post(`${BASE_URL}/users`, userPayload, { headers: defaultHeaders, tags: { name: 'POST_User' } });
  check(resUser, { 'POST User status 201': (r) => r.status === 201 });

  if (resUser.status === 201) {
    const userId = resUser.json('id');

    // 2. Cria Assinatura
    const subPayload = JSON.stringify({
      userId: userId,
      plan: 'PREMIUM',
      paymentToken: TOKENS[Math.floor(Math.random() * TOKENS.length)]
    });

    let resSub = http.post(`${BASE_URL}/subscriptions`, subPayload, { headers: defaultHeaders, tags: { name: 'POST_Sub' } });
    check(resSub, { 'POST Sub status 201': (r) => r.status === 201 });

    if (resSub.status === 201) {
      const subId = resSub.json('id');

      // =========================================================================
      // 3. MEDINDO O KAFKA (O Polling do Front-end)
      // =========================================================================
      let asyncStart = new Date();
      let isProcessed = false;
      let attempts = 0;

      // Fica perguntando até o Kafka resolver a cobrança (máx 50 tentativas / ~10 segundos)
      while (!isProcessed && attempts < 50) {
        let getSubRes = http.get(`${BASE_URL}/subscriptions/${subId}`, {
          headers: defaultHeaders,
          tags: { name: 'GET_Sub_Polling' }
        });

        if (getSubRes.status === 200) {
           let status = getSubRes.json('status');

           // Se mudou para ACTIVE ou SUSPENDED, a Saga do Kafka terminou!
           if (status === 'ACTIVE' || status === 'SUSPENDED') {
              isProcessed = true;
              kafkaLatencyTrend.add(new Date() - asyncStart); // Registra o tempo
           }
        }

        if (!isProcessed) {
           sleep(0.2); // Espera 200ms antes de perguntar de novo
           attempts++;
        }
      }

      // =========================================================================
      // 4. CANCELAMENTO (Aleatório)
      // =========================================================================
      if (Math.random() < 0.2) {
        let cancelRes = http.patch(`${BASE_URL}/subscriptions/${subId}/cancel`, "{}", {
          headers: { 'Content-Type': 'application/json', 'X-User-Id': userId },
          tags: { name: 'PATCH_Cancel' }
        });
        if (cancelRes.status === 204) cancelationCounter.add(1);
      }
    }
  }

  flowDurationTrend.add(new Date() - flowStart);
}