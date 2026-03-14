# 📦 Subscription Manager

## Introdução

Sistema de gestão de assinaturas recorrentes para um serviço de streaming. Usuários se cadastram, escolhem um plano mensal e o sistema cuida automaticamente de toda a cobrança: renovação no vencimento, retentativas com backoff exponencial em caso de falha, suspensão após o limite de tentativas e cancelamento com acesso garantido até o fim do ciclo pago.

O projeto foi construído como resposta a um desafio técnico, mas com uma barra de qualidade de produção: sem dual-write, sem cobrança duplicada, sem perda de estado entre reinicializações, escalável horizontalmente e com cobertura de testes em todas as camadas críticas.

---

## 🚀 Quick Start

### Pré-requisitos

- Docker + Docker Compose
- Java 21+
- (Opcional) `jq` para formatar as respostas JSON no terminal
- (Opcional) [`k6`](https://k6.io/docs/get-started/installation/) para o teste de carga

---

### Modo 1 — Desenvolvimento local (JVM direto)

Mais rápido para iterar em código. A infra sobe no Docker, a aplicação roda na sua JVM.

```bash
# 1. Suba a infra (Postgres, Kafka, Redis, Prometheus, Grafana)
docker compose up -d postgres kafka redis prometheus grafana

# 2. Aguarde ~15s e inicie a aplicação
./gradlew bootRun --args='--spring.profiles.active=local'
```

---

### Modo 2 — Stack completa no Docker (para stress test)

Tudo dentro do Docker Compose, incluindo a aplicação.

```bash
# 1. Gere o JAR (uma vez, ~30s — pula testes para ser mais rápido)
./gradlew bootJar -x test

# 2. Suba tudo (infra + app)
docker compose up --build

# Aguarde a mensagem "Started SubscriptionManagerApplication" nos logs do container app.
# Kafka leva ~30s para ficar healthy — o app aguarda automaticamente via depends_on.
```

Para resetar completamente os volumes entre runs:

```bash
docker compose down -v --remove-orphans
```

---

### Modo 3 — Stress test com o endpoint de seed

Com a stack rodando (Modo 1 ou Modo 2), use o script `stress-seed.sh` para exercitar o sistema em **duas frentes simultâneas** por ciclo.

#### Uso do script

```bash
# Sintaxe
./stress-seed.sh [count] [cycles] [http_users]

#   count       — assinaturas por chamada de seed         (padrão: 20)
#   cycles      — número de ciclos                         (padrão: 5)
#   http_users  — jornadas HTTP reais concorrentes/ciclo   (padrão: 5)
```

#### Exemplos

```bash
# Padrão: 5 ciclos × 20 subs (seed) + 5 jornadas HTTP/ciclo
./stress-seed.sh

# Carga média
./stress-seed.sh 50 10 10

# Carga alta
./stress-seed.sh 100 20 20
```

#### O que o script faz em cada ciclo

**Fase 1 — Scheduler load (async)**
1. **Seed** — `POST /api/test/seed?count=N` — insere N assinaturas distribuídas entre todos os cenários (ACTIVE, CANCELED expirando, SUSPENDED, beira do abismo)
2. **Sweep** — `POST /v1/admin/billing/trigger-sweep` — dispara o scheduler imediatamente

**Fase 2 — Jornadas HTTP reais (síncrono, em paralelo)**

Simula fluxo real de cadastro, em `http_users` goroutines bash simultâneas por ciclo:
1. `POST /v1/users` — cria usuário único
2. `POST /v1/subscriptions` — cria assinatura com `tok_test_success`
3. `GET /v1/subscriptions/{id}` — lê assinatura (aquece cache Redis)
4. `PATCH .../cancel` — cancela a assinatura em ~30% dos casos

Exercita transações, idempotência, cache Redis e circuit breaker no caminho síncrono — **independente do scheduler**.

Ao final de cada ciclo: **Snapshot** — contagem de assinaturas por status.

#### Comandos avulsos (sem o script)
```
# Workflow completo usando endpoints
TS=$(date +%s)

echo "=== STEP 1: Create User ==="
USER_BODY=$(curl -s -X POST http://localhost:8080/v1/users \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Rafael Demo\",\"document\":\"${TS:0:11}\",\"email\":\"demo_${TS}@test.com\"}")
echo "$USER_BODY"
USER_ID=$(echo "$USER_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

echo ""
echo "=== STEP 2: Create Subscription ==="
SUB_BODY=$(curl -s -X POST http://localhost:8080/v1/subscriptions \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"$USER_ID\",\"plan\":\"PREMIUM\",\"paymentToken\":\"tok_test_success\"}")
echo "$SUB_BODY"
SUB_ID=$(echo "$SUB_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

echo ""
echo "=== STEP 3: GET Subscription ==="
curl -s "http://localhost:8080/v1/subscriptions/$SUB_ID" -H "X-User-Id: $USER_ID"

echo ""
echo "=== STEP 4: Cancel ==="
curl -s -w "\nHTTP:%{http_code}" -X PATCH \
  "http://localhost:8080/v1/subscriptions/$SUB_ID/cancel" \
  -H "X-User-Id: $USER_ID"

echo ""
echo "=== STEP 5: GET after cancel ==="
curl -s "http://localhost:8080/v1/subscriptions/$SUB_ID" -H "X-User-Id: $USER_ID"

echo ""
echo "=== STEP 6: Reactivate CANCELED->ACTIVE (plan PREMIUM->BASICO) ==="
curl -s -X POST http://localhost:8080/v1/subscriptions \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"$USER_ID\",\"plan\":\"BASICO\",\"paymentToken\":\"tok_test_success\"}"

echo ""
echo "=== STEP 7: 409 — duplicate while ACTIVE ==="
curl -s -X POST http://localhost:8080/v1/subscriptions \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"$USER_ID\",\"plan\":\"FAMILIA\",\"paymentToken\":\"tok_test_success\"}"

echo ""
echo "=== DONE ==="
```
```bash
# Seed pontual
curl -s -X POST "http://localhost:8080/api/test/seed?count=20" | jq

# Sweep manual
curl -s -X POST http://localhost:8080/v1/admin/billing/trigger-sweep

# Estado atual de todas as assinaturas
curl -s http://localhost:8080/v1/admin/subscriptions | \
  jq '[.[] | {id, status, billing_attempts, next_retry_at}]'
```

---

### ✅ Como confirmar que o seed + sweep funcionaram corretamente

Após rodar o seed e o sweep (ou o `stress-seed.sh`), use o endpoint de verificação automática:

```bash
# Aguarde ~5s para o Kafka processar os resultados e execute:
curl -s http://localhost:8080/v1/admin/verify | jq .
```

O endpoint compara o estado atual do banco com os resultados esperados para cada cenário do seed e retorna um relatório objetivo:

```json
{
  "passed": true,
  "totalSubscriptions": 20,
  "checks": {
    "active": {
      "actual": 16,
      "expected_approx": 16,
      "passed": true,
      "detail_clean_renewal": 16,
      "detail_pending_retry": 0,
      "note": "ACTIVE total (~80%): renovadas limpas + pendentes de retry. Ambas são comportamento correto."
    },
    "suspended": {
      "actual": 2,
      "expected_approx": 2,
      "passed": true,
      "note": "SUSPENDED + autoRenew=false (~10% do total)"
    },
    "becameInactive": {
      "actual": 2,
      "expected_approx": 2,
      "passed": true,
      "note": "INACTIVE — eram CANCELED com expiringDate ≤ hoje (~10% do total)"
    },
    "outboxClean": {
      "pending": 0,
      "passed": true,
      "note": "Outbox limpo — todos os eventos processados."
    }
  },
  "checkedAt": "2026-02-22T19:11:05"
}
```

**Interpretação do resultado:**

| Campo | Significado |
|---|---|
| `passed: true` | Todos os cenários bateram com tolerância de 5% |
| `active` | Assinaturas renovadas com sucesso ou ativas pendentes de retry (~80% do seed) |
| `suspended` | Assinaturas suspensas após 3 falhas de cobrança (~10%) |
| `becameInactive` | Assinaturas canceladas que expiraram e viraram INACTIVE (~10%) |
| `outboxClean` | Nenhum evento pendente no outbox — Kafka processou tudo |

Se `passed: false`, o campo `hint` indica o próximo passo (ex: sweep ainda não rodou, aguarde o backoff das retries).

> **Dica:** Se `outboxClean.pending > 0`, aguarde mais 5–10 segundos e chame o endpoint novamente — o Kafka pode ainda estar processando as últimas mensagens.

---

### Modo 4 — Teste de carga com k6

Simula 50 usuários simultâneos criando assinaturas, fazendo polling do resultado via Kafka e cancelando aleatoriamente (~20% dos flows).

```bash
# Instalar k6 (macOS)
brew install k6

# Rodar o teste de carga contra a stack em execução
k6 run load-test.js
```

O teste dura 60s (15s rampa ↑ + 30s fogo cerrado + 15s rampa ↓) e mede:
- `http_req_duration` por endpoint (tag `name`)
- `kafka_e2e_processing_time` — latência end-to-end desde a criação até o Kafka resolver a cobrança
- `business_cancelations` — contador de cancelamentos bem-sucedidos
- Threshold: `p(95) < 200ms` no `GET /v1/subscriptions/{id}` (dominado pelo cache Redis)

> Usa apenas `tok_test_success` — para não abrir o circuit breaker durante o teste de carga.

---

### Rode os testes unitários e de integração

```bash
./gradlew test
```

---

### Serviços disponíveis

| Serviço | URL |
|---|---|
| API | `http://localhost:8080` |
| **Swagger UI** | **`http://localhost:8080/swagger-ui.html`** |
| **OpenAPI JSON** | **`http://localhost:8080/v3/api-docs`** |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3000` (admin/admin) |
| Métricas (raw) | `http://localhost:8080/actuator/prometheus` |

---


## 🎮 Cenários de Demonstração

> **Todos os cenários abaixo são executáveis com copy-paste.** A aplicação deve estar rodando com `--spring.profiles.active=local`.
>
> O backoff está configurado com `base-delay-minutes=1` — o ciclo completo de 3 falhas e suspensão é observável em **~7 minutos**.

### 📮 API Collections — Postman

Uma coleção pronta para importar e testar a API com auto-população de variáveis:

#### Arquivos

| Ferramenta | Arquivo | Tipo |
|---|---|---|
| **Postman** | `subscription-manager-postman.json` | Desktop app (mais recursos) |

#### Como usar

**Postman:**
1. Download: https://www.postman.com/downloads/
2. File → Import → Selecione `subscription-manager-postman.json`
3. Navegue até "🧪 Scenario 1" e clique Run
4. Variables auto-populam entre requests
5. Veja assertivas nos Test Results

#### Estrutura das coleções

```
Users/
  ├─ Create User (salva userId automaticamente)
  └─ Get User by ID

Subscriptions/
  ├─ Create Subscription (tok_test_success)
  ├─ Create Subscription (tok_test_always_fail)
  ├─ Get Subscription
  └─ Cancel Subscription

Admin/
  ├─ Trigger Sweep
  ├─ List All Subscriptions
  ├─ Force Suspend
  └─ Verify Business Scenarios

Test Utils/
  ├─ Seed (20 subscriptions)
  └─ Seed (2000 subscriptions)

🧪 Cenários
  ├─ Scenario 1 — Happy Path (criar user → sub → verificar ACTIVE)
  ├─ Scenario 2 — Cancellation (cancelar → verificar CANCELED)
  └─ Scenario 3 — Seed + Sweep (seed → sweep → verificar todos os states)
```

#### Workflows recomendados

**Happy Path (5 min):**
```
1. Scenario 1 — Happy Path
   → Cria user + subscription
   → Verifica status ACTIVE
```

**Full Demo (10 min):**
```
1. Scenario 3 — Seed + Sweep
   → Seeds 20 assinaturas diversas
   → Dispara sweep de renovação
   → Verifica 4 estados de negócio:
      ✅ ACTIVE (renovadas)
      ✅ SUSPENDED (falhas 3x)
      ✅ INACTIVE (expiradas)
      ✅ ACTIVE retry (falha 1x, recuperada)
```

**Cancelamento (3 min):**
```
1. Users → Create User
2. Subscriptions → Create Subscription
3. Scenario 2 — Cancellation
   → Verifica status CANCELED
   → Verifica autoRenew = false
```

#### Diferenças entre Postman e Hoppscotch

| Recurso | Postman | Hoppscotch |
|---|---|---|
| Instalação | App desktop | Browser (sem instalar) |
| Assertivas de teste | ✅ Sim (`pm.test()`) | ❌ Manual |
| Console de debug | ✅ Postman Console | ✅ Browser DevTools |
| Variable syntax | `{{variable}}` | `<<variable>>` |
| Recomendado para | QA/automatização | Exploração rápida |

---

### Tokens do Gateway (WireMock)

| Token | Comportamento |
|---|---|
| `tok_test_success` | Cobrança sempre aprovada ✅ |
| `tok_test_always_fail` | Cobrança sempre recusada — sub suspensa após 3 tentativas do sweeper ❌ |

> `tok_test_always_fail` é exercitado exclusivamente pelo **sweeper** (via seed com `billingAttempts=2`), nunca nas jornadas HTTP — para não abrir o circuit breaker no caminho síncrono.

---

### 🧪 Cenário 1 — Criação e renovação bem-sucedida

```bash
# 1. Criar usuário
USER=$(curl -s -X POST http://localhost:8080/v1/users \
  -H "Content-Type: application/json" \
  -d '{"name":"João Silva","email":"joao@email.com","document":"12345678901"}')
echo $USER | jq .
USER_ID=$(echo $USER | jq -r '.id')

# 2. Criar assinatura (tok_test_success → sempre aprovado)
SUB=$(curl -s -X POST http://localhost:8080/v1/subscriptions \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"$USER_ID\",\"plan\":\"PREMIUM\",\"paymentToken\":\"tok_test_success\"}")
echo $SUB | jq .
SUB_ID=$(echo $SUB | jq -r '.id')

# 3. Consultar assinatura
curl -s http://localhost:8080/v1/subscriptions/$SUB_ID \
  -H "X-User-Id: $USER_ID" | jq .
```

**Resultado esperado:** `status: ACTIVE`, `plan: PREMIUM`, cobrança de R$ 39,90 registrada.

---

### 🧪 Cenário 2 — Cancelamento com acesso até o fim do ciclo

```bash
# Continuando do Cenário 1 — $USER_ID e $SUB_ID já definidos

# Cancelar assinatura
curl -s -X PATCH http://localhost:8080/v1/subscriptions/$SUB_ID/cancel \
  -H "X-User-Id: $USER_ID" -w "\nHTTP %{http_code}\n"

# Consultar — deve estar CANCELED, autoRenew=false, mas expiringDate no futuro
curl -s http://localhost:8080/v1/subscriptions/$SUB_ID \
  -H "X-User-Id: $USER_ID" | jq '{status, autoRenew, expiringDate}'
```

**Resultado esperado:** `status: CANCELED`, `autoRenew: false`. O usuário conserva acesso até `expiringDate`. O scheduler moverá para `INACTIVE` após o vencimento.

---

### 🧪 Cenário 3 — Seed em massa + sweep manual (todos os cenários de negócio)

```bash
# 1. Gera 20 assinaturas representando os 3 perfis de negócio:
#    80% renovação (tok_test_success) | 10% beira do abismo (tok_test_always_fail, billingAttempts=2) | 10% cancelados
curl -s -X POST "http://localhost:8080/api/test/seed?count=20" | jq .

# 2. Consulta estado inicial (antes do sweep)
curl -s http://localhost:8080/v1/admin/subscriptions | jq '[.[] | {id, status, plan, billing_attempts, next_retry_at}]'

# 3. Dispara o sweep manualmente (não precisa esperar o scheduler de 1 min)
curl -s -X POST http://localhost:8080/v1/admin/billing/trigger-sweep -w "HTTP %{http_code}\n"

# 4. Aguarda ~5s e consulta novamente — observe as transições:
#    ACTIVE(billingAttempts=0) → renovadas
#    ACTIVE(billingAttempts=2, tok_test_always_fail) → SUSPENDED
#    CANCELED → INACTIVE
sleep 5
curl -s http://localhost:8080/v1/admin/subscriptions | jq '[.[] | {id, status, billing_attempts}]'

# 5. Verificação automática
curl -s http://localhost:8080/v1/admin/verify | jq .
```

**Resultado esperado após o sweep:**
- 16 assinaturas `ACTIVE` com `billing_attempts=0` (renovadas)
- 2 assinaturas `SUSPENDED` (atingiram 3 falhas com `tok_test_always_fail`)
- 2 assinaturas `INACTIVE` (eram `CANCELED` e venceram)

---

### 🧪 Cenário 4 — Suspensão automática após 3 falhas (via seed)

O seed já insere assinaturas com `billingAttempts=2` e `tok_test_always_fail`. O sweep faz a 3ª tentativa → suspensão automática.

```bash
# 1. Seed com perfil "beira do abismo" incluído (~10% das subs)
curl -s -X POST "http://localhost:8080/api/test/seed?count=20" | jq .

# 2. Dispara sweep
curl -s -X POST http://localhost:8080/v1/admin/billing/trigger-sweep

# 3. Aguarda Kafka processar e verifica
sleep 5
curl -s http://localhost:8080/v1/admin/verify | jq '{passed, checks: {suspended: .checks.suspended}}'
```

**Resultado esperado:** `checks.suspended.actual ≈ 2` (10% de 20), `passed: true`.
As subs suspensas têm `status: SUSPENDED`, `autoRenew: false`, `next_retry_at: null`.

---

### 🧪 Cenário 5 — Verificação automática pós-seed (endpoint de health check de negócio)

```bash
# Seed + sweep + verify em sequência
curl -s -X POST "http://localhost:8080/api/test/seed?count=20" | jq '.totalSeeded'
curl -s -X POST http://localhost:8080/v1/admin/billing/trigger-sweep
sleep 5
curl -s http://localhost:8080/v1/admin/verify | jq .
```

**Resultado esperado completo:**

```json
{
  "passed": true,
  "checks": {
    "active":         { "actual": 16, "passed": true },
    "suspended":      { "actual": 2,  "passed": true },
    "becameInactive": { "actual": 2,  "passed": true },
    "outboxClean":    { "pending": 0, "passed": true }
  }
}
```

---

### 🧪 Cenário 6 — Validação isolada dos dois schedulers

Usa o script `scheduler-validation.sh` para validar `RenewalOrchestratorService` e `SubscriptionExpiryService` em **isolamento e combinado**, sem precisar analisar logs manualmente.

```bash
./scheduler-validation.sh               # contra http://localhost:8080
./scheduler-validation.sh http://host:8080  # outro target
```

O script roda 4 seções (A–D) e exibe ✔/✘ em cada check:

| Seção | O que valida |
|---|---|
| **A — RenewalScheduler** | Seed + `trigger-sweep`; confere ACTIVE ≈ 16, SUSPENDED ≈ 2, renovações limpas ≥ 10, outbox = 0 |
| **B — ExpiryScheduler** | Cria assinatura, cancela, dispara `trigger-expiry-sweep`, verifica resposta com contagem |
| **C — Combinado** | `/v1/admin/verify` com tolerância de 15% para os dois schedulers juntos |
| **D — Referência curl** | Imprime todos os comandos avulsos para uso interativo |

**Acionar cada scheduler isoladamente:**

```bash
# Ambos em sequência (renewal primeiro, expiry depois)
curl -s -X POST http://localhost:8080/v1/admin/billing/trigger-sweep

# Apenas o SubscriptionExpiryService (CANCELED → INACTIVE)
curl -s -X POST http://localhost:8080/v1/admin/billing/trigger-expiry-sweep | jq .
# {"expiredToInactive": 2, "message": "2 assinatura(s) movida(s) de CANCELED → INACTIVE."}

# Confirmar nos logs que os dois schedulers são independentes:
docker logs -f subscription-manager 2>&1 | grep -E '\[(RENEWAL|EXPIRY)\]'
# 📋 [RENEWAL] Processando página 0 com N subscrições elegíveis.
# 🗓️ [EXPIRY] N assinatura(s) movida(s) para INACTIVE neste ciclo.
```

| Grupo | % seed | Token | Resultado após sweep |
|---|---|---|---|
| 70% renovação normal | `billingAttempts=0` | `tok_test_success` | `ACTIVE` renovada |
| 10% retry (falhou 1x) | `billingAttempts=1` | `tok_test_success` | `ACTIVE` renovada |
| 10% beira do abismo | `billingAttempts=2` | `tok_test_always_fail` | `SUSPENDED` |
| 10% cancelados vencidos | `CANCELED` | — | `INACTIVE` |



---

## 🛠️ Tecnologias

| Categoria | Tecnologia |
|---|---|
| Linguagem | Java 21 (Virtual Threads) |
| Framework principal | Spring Boot 3 / Spring Modulith |
| Banco de dados | PostgreSQL 15 |
| Mensageria | Apache Kafka (KRaft, Confluent 7.6) |
| Cache | Redis (Lettuce) |
| Migrações | Flyway |
| Lock distribuído | ShedLock (JDBC) |
| Resiliência | Resilience4j (Circuit Breaker + Retry) |
| Criptografia | AES-256-GCM (token de pagamento) |
| Mock de gateway | WireMock (perfil `local`) |
| Observabilidade | Micrometer + Prometheus |
| Testes | JUnit 5 + Mockito + Spring Modulith Verification |
| Build | Gradle (Kotlin DSL) |
| Conteinerização | Docker Compose |
| Redução de boilerplate | Lombok |

---

## 🗄️ MER — Modelo Entidade-Relacionamento

```
┌──────────────────────────────────┐
│              users               │
├──────────────────────────────────┤
│ id            UUID  PK           │
│ name          VARCHAR(255)       │
│ document      VARCHAR(20)  UNIQUE│
│ email         VARCHAR(255) UNIQUE│
│ created_at    TIMESTAMP          │
│ updated_at    TIMESTAMP          │
└──────────────────┬───────────────┘
                   │ 1
                   │
                   │ 1
┌──────────────────▼───────────────┐
│           subscriptions          │
├──────────────────────────────────┤
│ id                UUID  PK       │
│ user_id           UUID  FK→users │
│ plan              VARCHAR(20)    │  ← BASICO | PREMIUM | FAMILIA
│ status            VARCHAR(20)    │  ← ACTIVE | CANCELED | SUSPENDED | INACTIVE
│ payment_token     VARCHAR(255)   │  ← AES-256-GCM encrypted
│ start_date        TIMESTAMP      │
│ expiring_date     TIMESTAMP      │
│ auto_renew        BOOLEAN        │
│ billing_attempts  INT            │
│ next_retry_at     TIMESTAMP      │
│ last_billing_attempt TIMESTAMP   │
│ created_at        TIMESTAMP      │
│ updated_at        TIMESTAMP      │
└──────────────────┬───────────────┘
                   │ 1
                   │
                   │ N
┌──────────────────▼───────────────┐
│          billing_history         │
├──────────────────────────────────┤
│ id                    UUID  PK   │
│ subscription_id       UUID  FK   │
│ idempotency_key       VARCHAR    │  ← UNIQUE: {subId}-{ano}-{mes}-attempt-{n}
│ status                VARCHAR    │  ← PENDING | SUCCESS | FAILED
│ gateway_transaction_id VARCHAR   │
│ processed_at          TIMESTAMP  │
│ created_at            TIMESTAMP  │
│ updated_at            TIMESTAMP  │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│         event_publication        │  ← Outbox gerenciado pelo Spring Modulith
├──────────────────────────────────┤
│ id                    UUID  PK   │
│ listener_id           VARCHAR    │
│ event_type            VARCHAR    │
│ serialized_event      TEXT       │
│ publication_date      TIMESTAMP  │
│ completion_date       TIMESTAMP  │
│ status                VARCHAR    │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│            shedlock              │  ← Lock distribuído do scheduler
├──────────────────────────────────┤
│ name        VARCHAR  PK          │
│ lock_until  TIMESTAMP            │
│ locked_at   TIMESTAMP            │
│ locked_by   VARCHAR              │
└──────────────────────────────────┘
```

**Índices relevantes:**

- `idx_sub_renewal_sweep` em `subscriptions(next_retry_at) WHERE status='ACTIVE' AND auto_renew=true` — query de varredura do scheduler
- `idx_sub_api_read` em `subscriptions(user_id) INCLUDE (status, plan, expiring_date)` — leitura e cancelamento via API
- `uk_billing_idempotency` em `billing_history(idempotency_key)` — constraint de unicidade que impede cobrança dupla
- `idx_event_publication_incomplete` em `event_publication(completion_date) WHERE completion_date IS NULL` — reprocessamento do outbox

---

## ✅ Checklist do Desafio

### Requisitos obrigatórios

- [x] API para cadastrar usuários
- [x] API para criar assinatura — um usuário só pode ter uma assinatura ativa por vez
- [x] Assinatura com `id`, `userId`, `plano`, `dataInicio`, `dataExpiracao`, `status`
- [x] Planos: 
    - `BASICO` (R$ 19,90), 
    - `PREMIUM` (R$ 39,90) ,
    - `FAMILIA` (R$ 59,90)
- [x] Agendador que renova assinaturas automaticamente no vencimento
- [x] Suspensão após 3 tentativas de renovação falhadas
- [x] Endpoint de cancelamento com acesso preservado até o fim do ciclo

### Diferenciais

- [x] Spring Boot como framework principal
- [x] Persistência com PostgreSQL
- [x] Eventos assíncronos com Kafka para processar pagamentos
- [x] Cache com Redis para otimizar consultas de assinaturas ativas
- [x] Testes automatizados com JUnit 5 + Mockito
- [x] Deploy com Docker Compose

### Além do desafio

- [x] Spring Modulith — fronteiras de módulo verificadas em tempo de build
- [x] Padrão Outbox — eliminação de dual-write entre banco e Kafka
- [x] Idempotência de cobrança em duas camadas (aplicação + constraint única no banco)
- [x] Criptografia AES-256-GCM no token de pagamento em repouso
- [x] Circuit Breaker (Resilience4j) no cliente do gateway de pagamento
- [x] Backoff exponencial nas retentativas de renovação
- [x] ShedLock — scheduler distribuído, sem execução duplicada em múltiplos nós
- [x] Updates em lote de alta performance via SQL nativo (`FOR UPDATE SKIP LOCKED`)
- [x] Virtual Threads (Java 21) no processamento em lote do Kafka
- [x] TTL inteligente no Redis por status (ACTIVE/CANCELED vs SUSPENDED/INACTIVE)
- [x] WireMock embarcado para simular o gateway localmente
- [x] Seeder de dados realistas para smoke tests (`POST /api/test/seed`)
- [x] Endpoint de disparo manual do scheduler (`POST /v1/admin/billing/trigger-sweep`)
- [x] Scheduler separado para expiração de CANCELED → INACTIVE (`SubscriptionExpiryService`, ShedLock `expirySweepTask`)
- [x] Endpoint dedicado para acionar somente o expiry sweep (`POST /v1/admin/billing/trigger-expiry-sweep`)
- [x] Script de validação isolada dos schedulers (`scheduler-validation.sh`)
- [x] Observabilidade com Micrometer + Prometheus
- [x] Migrações schema-first com Flyway (`ddl-auto=none`)
- [x] Cache com estratégia de TTL para evitar thundering herd
- [x] Testes de modularidade (`ModularityTests`) garantindo encapsulamento de módulos

### Não implementado / trabalho futuro

- [ ] Autenticação e autorização (JWT / OAuth2) — endpoints públicos no estado atual
- [x] Expiração automática de assinaturas `CANCELED` via `SubscriptionExpiryService`
- [ ] Endpoint de reativação de assinatura suspensa com troca de cartão via API documentada
- [ ] Notificações ao usuário (e-mail / push) em eventos de cobrança, suspensão e expiração
- [ ] Testes de integração end-to-end com Testcontainers (PostgreSQL + Kafka + Redis reais)
- [ ] Deploy em cloud (AWS ECS / GCP Cloud Run) com variáveis de ambiente seguras
- [x] Documentação da API com OpenAPI / Swagger UI
- [ ] Estratégia de write-behind e flush de assinaturas em uma tópico de processamento em lote
- [ ] Contemplar meios de pagamento e escrever estratégias para chamadas dos gateways de pagamento
- [ ] Rodar mais testes de carga com K6 -> ajustar JVM, GC, memória e CPU, melhorar configs do Kafka
- [ ] Suporte a múltiplas moedas e gateways de pagamento

---

## 🏛️ Arquitetura

### Rascunho de projeto

![System Design](./system-design.png)

---

### Visão completa — fronteiras externas, módulos internos e infraestrutura

```
                      ║  ┌──────────────────┐  ┌───────────────────────────┐ ║
                      ║  │       user       │◄─│       subscription        │ ║
                      ║  │                  │  │                           │ ║
                      ║  │ POST /v1/users   │  │ POST /v1/subscriptions    │ ║
                      ║  │ GET  /v1/users   │  │ GET  /v1/subscriptions/id │ ║
                      ║  │                  │  │ PATCH .../cancel          │ ║
                      ║  │ UserFacade       │  │                           │ ║
                      ║  │ (Port/iface)     │  │ SubscriptionService       │ ║
                      ║  └──────────────────┘  │ SubscriptionWriteService  │ ║
                      ║                        │ RenewalOrchestratorService│ ║
                      ║                        │ SubscriptionExpiryService │ ║
                      ║                        │ SubscriptionResultListener│ ║
                      ║                        │ SubscriptionCacheUpdater  │ ║
                      ║                        └───────────────────────────┘ ║
                      ║                                                     ║
                      ║  ┌─────────────────────────────────────────────┐   ║
                      ║  │                  billing                    │   ║
                      ║  │                                             │   ║
                      ║  │  BillingFacade      (Port/iface)            │   ║
                      ║  │  BillingFacadeImpl                          │   ║
                      ║  │  BillingWorker ◄── subscription.renewals    │   ║
                      ║  │  PaymentTokenPort   (Port/iface)            │   ║
                      ║  │  PaymentGatewayClient ──────────────────────────────┐
                      ║  │  BillingHistoryRepository                   │   ║   │ HTTP
                      ║  │                                             │   ║   │ POST /v1/charges
                      ║  │  POST /v1/admin/billing/trigger-sweep          │   ║   │
                      ║  │  POST /v1/admin/billing/trigger-expiry-sweep   │   ║   │
                      ║  │  GET  /v1/admin/subscriptions               │   ║   │
                      ║  │  POST /v1/admin/subscriptions/{id}/suspend  │   ║   ▼
                      ║  └─────────────────────────────────────────────┘   ║  ┌─────────────────────┐
                      ║                                                     ║  │  Gateway Pagamento  │
                      ║  ┌─────────────────────────────────────────────┐   ║  │     (Externo)       │
                      ║  │                   shared                    │   ║  │                     │
                      ║  │  Events (Records) · Exceptions              │   ║  │  Stripe / Pagar.me  │
                      ║  │  PaymentTokenConverter (AES-256-GCM)        │   ║  │  ou qualquer outro  │
                      ║  │  Kafka Config · DTOs / Records              │   ║  │                     │
                      ║  └─────────────────────────────────────────────┘   ║  │  ← WireMock apenas  │
                      ╚═════════════════════════════════════════════════════╝  │    no perfil local  │
                                             │                                 └─────────────────────┘
              ┌──────────────────────────────┼──────────────────┐
              │                              │                  │
       ┌──────▼──────┐              ┌────────▼──────┐   ┌───────▼──────────┐
       │  PostgreSQL │              │     Kafka     │   │      Redis       │
       │             │              │               │   │                  │
       │  users      │              │  .renewals    │   │  subscription:   │
       │  subscript. │              │  .billing-    │   │  user:{userId}   │
       │  billing_h. │              │   results     │   │                  │
       │  event_pub. │              └───────────────┘   └──────────────────┘
       │  shedlock   │
       └─────────────┘
```

---

### Fluxo de chamadas — endpoints HTTP (iniciados pelo frontend)

```
Frontend / BFF
     │
     ├── POST /v1/users
     │       └── UserController
     │               └── UserService.create(CreateUserDTO)
     │                       └── UserRepository.save()  ──▶  PostgreSQL
     │                               └── 201 Created { id, name, email }
     │
     ├── POST /v1/subscriptions          (body: { userId, plan, paymentToken })
     │       └── SubscriptionController
     │               └── SubscriptionService.create(CreateSubscriptionDTO)
     │                       └── SubscriptionWriteService.createAndCharge()
     │                               ├── TX #1: saveSubscription()      ──▶ PostgreSQL
     │                               ├── BillingFacade.chargeForNewSubscription()
     │                               │       └── PaymentGatewayClient.charge()  ──▶ Gateway Externo
     │                               │               ├── SUCESSO → retorna SubscriptionUpdatedEvent
     │                               │               └── FALHA   → revertReactivation()  ──▶ PostgreSQL
     │                               └── transactionTemplate.executeWithoutResult()
     │                                       └── publishEvent(SubscriptionUpdatedEvent) ──▶ @TransactionalEventListener(AFTER_COMMIT)
     │                                               └── SubscriptionCacheUpdater ──▶ Redis (status=ACTIVE,
