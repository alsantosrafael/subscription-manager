package com.platform.subscription_manager.subscription.infrastructure.web.controller;

import com.platform.subscription_manager.subscription.application.services.AdminService;
import com.platform.subscription_manager.subscription.application.services.RenewalOrchestratorService;
import com.platform.subscription_manager.subscription.application.services.SubscriptionExpiryService;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin")
@Tag(name = "Admin", description = """
	Endpoints de operação e observabilidade.
	Permitem acionar o scheduler manualmente, inspecionar o estado de todas as assinaturas
	e forçar transições de estado para demonstração — não devem ser expostos em produção.
	""")
public class AdminBillingController {

	private static final Logger log = LoggerFactory.getLogger(AdminBillingController.class);

	private final RenewalOrchestratorService orchestrator;
	private final SubscriptionExpiryService expiryService;
	private final SubscriptionRepository subscriptionRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final TransactionTemplate transactionTemplate;
	private final AdminService adminService;

	public AdminBillingController(RenewalOrchestratorService orchestrator,
								  SubscriptionExpiryService expiryService,
								  SubscriptionRepository subscriptionRepository,
								  ApplicationEventPublisher eventPublisher,
								  TransactionTemplate transactionTemplate,
								  AdminService adminService) {
		this.orchestrator = orchestrator;
		this.expiryService = expiryService;
		this.subscriptionRepository = subscriptionRepository;
		this.eventPublisher = eventPublisher;
		this.transactionTemplate = transactionTemplate;
		this.adminService = adminService;
	}

	@Operation(
		summary = "Disparar sweep de renovações",
		description = """
			Aciona imediatamente o mesmo fluxo que o scheduler executa a cada minuto:
			1. Identifica assinaturas elegíveis (`ACTIVE`, `autoRenew=true`, `nextRetryAt ≤ agora`, `billingAttempts < maxAttempts`)
			2. Persiste `nextRetryAt = now + inFlightGuardMinutes` e despacha evento via Kafka (Outbox Pattern)
			3. Move assinaturas `CANCELED` cujo `expiringDate ≤ agora` para `INACTIVE` (via `expirySweepTask`)

			Use após `POST /api/test/seed` para observar as transições de estado sem esperar 1 minuto.
			""",
		responses = {
			@ApiResponse(responseCode = "202", description = "Sweep iniciado — processamento assíncrono via Kafka (aguarde ~3s para ver os resultados)")
		}
	)
	@PostMapping("/billing/trigger-sweep")
	public ResponseEntity<Void> triggerSweep() {
		log.info("⚙️ Gatilho manual de faturamento acionado via API Administrativa.");
		orchestrator.executeDailySweep();
		expiryService.runExpirySweep();
		return ResponseEntity.accepted().build();
	}

	@Operation(
		summary = "Disparar somente o sweep de expiração",
		description = """
			Aciona exclusivamente o `SubscriptionExpiryService`: move assinaturas `CANCELED` cujo
			`expiringDate ≤ agora` para `INACTIVE`, sem tocar nas renovações.

			Útil para validar o scheduler de expiração isoladamente — por exemplo após criar uma
			assinatura via `POST /v1/subscriptions` e cancelá-la com data já vencida.
			""",
		responses = {
			@ApiResponse(responseCode = "202", description = "Expiry sweep concluído de forma síncrona neste request")
		}
	)
	@PostMapping("/billing/trigger-expiry-sweep")
	public ResponseEntity<Map<String, Object>> triggerExpirySweep() {
		log.info("⚙️ Gatilho manual de expiração acionado via API Administrativa.");
		int expired = expiryService.expireCanceledSubscriptions(LocalDateTime.now());
		return ResponseEntity.accepted().body(Map.of(
			"expiredToInactive", expired,
			"message", expired > 0
				? expired + " assinatura(s) movida(s) de CANCELED → INACTIVE."
				: "Nenhuma assinatura CANCELED vencida encontrada."
		));
	}

	@Operation(
		summary = "Listar todas as assinaturas",
		description = """
			Retorna todas as assinaturas ordenadas por status (SUSPENDED primeiro) e `billing_attempts DESC`.
			Use para inspecionar transições de estado durante demonstrações — evita acesso direto ao banco.
			Não expõe o `payment_token` (sempre nulo neste endpoint).
			""",
		responses = {
			@ApiResponse(responseCode = "200", description = "Lista de assinaturas com campos operacionais")
		}
	)
	@GetMapping("/subscriptions")
	public ResponseEntity<List<Map<String, Object>>> listSubscriptions() {
		return ResponseEntity.ok(adminService.listAllSubscriptions());
	}

	@Operation(
		summary = "Forçar suspensão de assinatura",
		description = """
			Suspende imediatamente uma assinatura `ACTIVE` usando o mesmo caminho atômico do scheduler
			(`suspendSubscriptionAtomic`) — garantia de idempotência e sem race conditions.

			Útil para demonstrar o cenário de suspensão sem aguardar 3 ciclos reais de falha de pagamento.

			**Efeitos:**
			- `status` → `SUSPENDED`
			- `autoRenew` → `false`
			- `nextRetryAt` → `null`
			- Cache Redis atualizado via evento `SubscriptionUpdatedEvent`

			Retorna `404` se a assinatura não existir ou não estiver em estado `ACTIVE`.
			""",
		responses = {
			@ApiResponse(responseCode = "204", description = "Assinatura suspensa com sucesso"),
			@ApiResponse(responseCode = "404", description = "Assinatura não encontrada ou não está ACTIVE", content = @Content)
		}
	)
	@PostMapping("/subscriptions/{id}/force-suspend")
	public ResponseEntity<Void> forceSuspend(
		@Parameter(description = "UUID da assinatura a suspender", required = true) @PathVariable UUID id) {
		return subscriptionRepository.findById(id)
			.filter(sub -> sub.getStatus() == SubscriptionStatus.ACTIVE)
			.map(sub -> {
				transactionTemplate.executeWithoutResult(status -> {
					int updated = subscriptionRepository.suspendSubscriptionAtomic(
						sub.getId(),
						sub.getExpiringDate(),
						LocalDateTime.now(),
						sub.getBillingAttempts()
					);
					if (updated > 0) {
						log.info("🔧 [ADMIN] Assinatura {} forçada para SUSPENDED via API admin.", id);
						eventPublisher.publishEvent(new SubscriptionUpdatedEvent(
							sub.getId(), sub.getUserId(), SubscriptionStatus.SUSPENDED,
							sub.getPlan(), sub.getStartDate(), sub.getExpiringDate(), false
						));
					}
				});
				return ResponseEntity.noContent().<Void>build();
			})
			.orElse(ResponseEntity.notFound().build());
	}

	@Operation(
		summary = "Histórico de cobranças de uma assinatura",
		description = """
			Retorna todos os registros de `billing_history` para uma assinatura, do mais recente ao mais antigo.

			Cada entrada representa uma tentativa de cobrança — inicial ou de renovação automática.

			**Campos:**
			| Campo | Significado |
			|---|---|
			| `status` | `PENDING` → `SUCCESS` ou `FAILED` |
			| `gateway_transaction_id` | ID da transação no gateway (null em caso de falha) |
			| `processed_at` | Quando a cobrança foi finalizada (null enquanto PENDING) |
			| `created_at` | Quando a tentativa foi registrada |
			| `idempotency_key` | Chave única que previne cobranças duplicadas em retentativas |
			""",
		responses = {
			@ApiResponse(responseCode = "200", description = "Histórico encontrado (lista vazia se nenhuma cobrança foi tentada)"),
			@ApiResponse(responseCode = "404", description = "Assinatura não encontrada", content = @Content)
		}
	)
	@GetMapping("/subscriptions/{id}/billing-history")
	public ResponseEntity<List<Map<String, Object>>> billingHistory(
		@Parameter(description = "UUID da assinatura", required = true) @PathVariable UUID id) {
		if (!subscriptionRepository.existsById(id)) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(adminService.getBillingHistory(id));
	}

	@Operation(
		summary = "Verificar resultado pós-seed + sweep",
		description = """
			Compara o estado atual do banco com os resultados esperados de uma rodada de seed + sweep.
			Útil para confirmar de forma objetiva que o sistema funcionou corretamente.

			**Regras de verificação:**
			| Cenário | Condição de PASS |
			|---|---|
			| Happy path (70%) | status=ACTIVE, billingAttempts=0, expiringDate > hoje |
			| Retry 1x (10%) | status=ACTIVE, billingAttempts=0, expiringDate > hoje |
			| Suspensão (10%) | status=SUSPENDED, autoRenew=false |
			| Cancelados (10%) | status=INACTIVE |

			Retorna `passed=true` se todos os cenários batem dentro de 5% de tolerância.
			""",
		responses = {
			@ApiResponse(responseCode = "200", description = "Resultado da verificação com detalhe por cenário")
		}
	)
	@GetMapping("/verify")
	public ResponseEntity<Map<String, Object>> verifySeedResult() {
		Map<String, Object> result = adminService.verifySeedResult();
		log.info("🔍 [ADMIN] Verificação pós-seed: passed={}", result.get("passed"));
		return ResponseEntity.ok(result);
	}

	@Operation(
		summary = "Snapshot consolidado do sistema",
		description = """
			Retorna um snapshot consolidado das assinaturas, organizado por status.
			Use logo após `POST /v1/admin/billing/trigger-sweep` (aguarde ~3s pelo processamento Kafka)
			para confirmar que o sistema funcionou corretamente.

			**O que esperar após seed de 20 registros + sweep:**
			| Status | Esperado | Motivo |
			|---|---|---|
			| `ACTIVE` — renovadas | ~70% | Pagamento aprovado, `expiringDate` avançou |
			| `ACTIVE` — com retry | ~10% | Pagamento aprov. na 2ª tentativa (1 falha anterior) |
			| `SUSPENDED` | ~10% | 3ª falha consecutiva, `autoRenew=false` |
			| `INACTIVE` | ~10% | Eram `CANCELED` e atingiram `expiringDate` |

			**Campos de diagnóstico incluídos:**
			- `countsByStatus` — contagens por status
			- `successfulRenewals` — renovadas neste ciclo (`expiringDate` > hoje e `billingAttempts = 0`)
			- `pendingRetries` — aguardando retry (`ACTIVE`, `billingAttempts > 0`, `nextRetryAt` no futuro)
			- `stuckInFlight` — possível bug: `nextRetryAt` no passado mas ainda ACTIVE com attempts > 0
			- `outboxPending` — eventos não processados no Outbox (deve ser 0 após ~5s)
			""",
		responses = {
			@ApiResponse(responseCode = "200", description = "Snapshot do estado do sistema")
		}
	)
	@GetMapping("/status")
	public ResponseEntity<Map<String, Object>> systemStatus() {
		Map<String, Object> result = adminService.systemStatus();
		log.info("📊 [ADMIN] Status consultado: total={}, outbox={}",
			result.get("totalSubscriptions"), result.get("outboxPending"));
		return ResponseEntity.ok(result);
	}
}
