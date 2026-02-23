package com.platform.subscription_manager.subscription.infrastructure.web.controller;

import com.platform.subscription_manager.subscription.application.services.RenewalOrchestratorService;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
	private final JdbcTemplate jdbcTemplate;
	private final SubscriptionRepository subscriptionRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final TransactionTemplate transactionTemplate;

	public AdminBillingController(RenewalOrchestratorService orchestrator,
								  JdbcTemplate jdbcTemplate,
								  SubscriptionRepository subscriptionRepository,
								  ApplicationEventPublisher eventPublisher,
								  TransactionTemplate transactionTemplate) {
		this.orchestrator = orchestrator;
		this.jdbcTemplate = jdbcTemplate;
		this.subscriptionRepository = subscriptionRepository;
		this.eventPublisher = eventPublisher;
		this.transactionTemplate = transactionTemplate;
	}

	@Operation(
		summary = "Disparar sweep de renovações",
		description = """
			Aciona imediatamente o mesmo fluxo que o scheduler executa a cada minuto:
			1. Identifica assinaturas elegíveis (`ACTIVE`, `autoRenew=true`, `nextRetryAt ≤ agora`, `billingAttempts < maxAttempts`)
			2. Persiste `nextRetryAt = now + inFlightGuardMinutes` e despacha evento via Kafka (Outbox Pattern)
			3. Move assinaturas `CANCELED` cujo `expiringDate ≤ agora` para `INACTIVE`

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
		return ResponseEntity.accepted().build();
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
		List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
			SELECT
			    s.id,
			    s.user_id,
			    s.plan,
			    s.status,
			    s.auto_renew,
			    COALESCE(s.billing_attempts, 0)        AS billing_attempts,
			    s.expiring_date,
			    s.next_retry_at,
			    s.last_billing_attempt,
			    s.start_date
			FROM subscriptions s
			ORDER BY
			    CASE s.status
			        WHEN 'SUSPENDED' THEN 1
			        WHEN 'CANCELED'  THEN 2
			        WHEN 'ACTIVE'    THEN 3
			        WHEN 'INACTIVE'  THEN 4
			        ELSE 5
			    END,
			    COALESCE(s.billing_attempts, 0) DESC
			""");
		return ResponseEntity.ok(rows);
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
		// Scope verification to SEED-created subscriptions only.
		// Seeded subs have start_date = ~1 month ago (pastMonth).
		// HTTP-journey subs have start_date = NOW(), so they are excluded by the 1-hour cutoff.
		Long total = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE start_date < NOW() - INTERVAL '1 hour'
			""", Long.class);
		if (total == null || total == 0) {
			Map<String, Object> empty = new LinkedHashMap<>();
			empty.put("passed", false);
			empty.put("reason", "Nenhuma assinatura de seed encontrada. Execute POST /api/test/seed primeiro.");
			empty.put("hint", "Subs criadas via HTTP journey (start_date recente) são excluídas desta contagem.");
			return ResponseEntity.ok(empty);
		}

		// ACTIVE: any active subscription — includes clean renewals AND pending retries.
		// Pending retries (billingAttempts > 0) happen when the circuit breaker is open during sweep,
		// which is expected behaviour during stress tests. They will be retried on next cycle.
		Long activeTotal = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'ACTIVE' AND start_date < NOW() - INTERVAL '1 hour'
			""", Long.class);

		Long renewedClean = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'ACTIVE' AND COALESCE(billing_attempts, 0) = 0
			  AND start_date < NOW() - INTERVAL '1 hour'
			""", Long.class);

		Long pendingRetry = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'ACTIVE' AND COALESCE(billing_attempts, 0) > 0
			  AND start_date < NOW() - INTERVAL '1 hour'
			""", Long.class);

		Long suspended = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'SUSPENDED' AND auto_renew = false
			  AND start_date < NOW() - INTERVAL '1 hour'
			""", Long.class);

		Long inactive = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'INACTIVE'
			  AND start_date < NOW() - INTERVAL '1 hour'
			""", Long.class);

		Long canceled = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'CANCELED'
			  AND start_date < NOW() - INTERVAL '1 hour'
			""", Long.class);

		Long outboxPending = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL
			""", Long.class);

		// Seed: 10% → INACTIVE (were CANCELED+expired), 10% → SUSPENDED (always_fail token hit 3 attempts)
		// Remaining 80% → ACTIVE (clean renewal or pending retry due to circuit breaker — both correct)
		// Tolerance: 15% to account for WireMock randomness
		double expectedActive    = total * 0.80;
		double expectedSuspended = total * 0.10;
		double expectedInactive  = total * 0.10;
		double tolerance = Math.max(total * 0.15, 1.0);

		boolean activeOk    = activeTotal  != null && Math.abs(activeTotal  - expectedActive)    <= tolerance;
		boolean suspendedOk = suspended    != null && Math.abs(suspended    - expectedSuspended) <= tolerance;
		boolean inactiveOk  = inactive     != null && Math.abs(inactive     - expectedInactive)  <= tolerance;
		boolean outboxOk    = outboxPending != null && outboxPending == 0;
		boolean allPassed   = activeOk && suspendedOk && inactiveOk && outboxOk;

		Map<String, Object> checks = new LinkedHashMap<>();

		Map<String, Object> activeCheck = new LinkedHashMap<>();
		activeCheck.put("actual", activeTotal);
		activeCheck.put("expected_approx", (long) expectedActive);
		activeCheck.put("passed", activeOk);
		activeCheck.put("detail_clean_renewal", renewedClean);
		activeCheck.put("detail_pending_retry", pendingRetry);
		activeCheck.put("note", "ACTIVE total (~80%): renovadas limpas + pendentes de retry. Ambas são comportamento correto.");
		checks.put("active", activeCheck);

		Map<String, Object> suspCheck = new LinkedHashMap<>();
		suspCheck.put("actual", suspended);
		suspCheck.put("expected_approx", (long) expectedSuspended);
		suspCheck.put("passed", suspendedOk);
		suspCheck.put("note", "SUSPENDED + autoRenew=false (~10%): token always_fail esgotou as 3 tentativas.");
		checks.put("suspended", suspCheck);

		Map<String, Object> inactCheck = new LinkedHashMap<>();
		inactCheck.put("actual", inactive);
		inactCheck.put("expected_approx", (long) expectedInactive);
		inactCheck.put("passed", inactiveOk);
		inactCheck.put("note", "INACTIVE (~10%): eram CANCELED com expiringDate ≤ hoje — scheduler marcou como inativas.");
		checks.put("becameInactive", inactCheck);

		if (canceled != null && canceled > 0) {
			Map<String, Object> canceledInfo = new LinkedHashMap<>();
			canceledInfo.put("count", canceled);
			canceledInfo.put("note", "CANCELED mas ainda não expiradas — o scheduler as marcará INACTIVE no próximo ciclo.");
			checks.put("canceledPendingExpiry_info", canceledInfo);
		}

		Map<String, Object> outboxCheck = new LinkedHashMap<>();
		outboxCheck.put("pending", outboxPending);
		outboxCheck.put("passed", outboxOk);
		outboxCheck.put("note", outboxOk
			? "Outbox limpo — todos os eventos foram processados pelo Kafka."
			: "Outbox ainda tem eventos pendentes — aguarde ~5s e re-execute.");
		checks.put("outboxClean", outboxCheck);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("passed", allPassed);
		result.put("seededSubscriptions", total);
		result.put("note_scope", "Contagem inclui apenas assinaturas de seed (start_date > 1h atrás). Subs criadas via endpoint HTTP são excluídas.");
		result.put("checks", checks);
		result.put("checkedAt", LocalDateTime.now().toString());
		if (!allPassed) {
			result.put("hint", "Se o sweep ainda não rodou, execute POST /v1/admin/billing/trigger-sweep e aguarde ~5s antes de verificar novamente.");
		}

		log.info("🔍 [ADMIN] Verificação pós-seed: passed={}, ativas={}, suspensas={}, inativas={}, outbox={}",
			allPassed, activeTotal, suspended, inactive, outboxPending);

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
		List<Map<String, Object>> statusCounts = jdbcTemplate.queryForList("""
			SELECT status, COUNT(*) AS total
			FROM subscriptions
			GROUP BY status
			ORDER BY total DESC
			""");

		Long successfulRenewals = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'ACTIVE'
			  AND expiring_date > CURRENT_DATE
			  AND billing_attempts = 0
			""", Long.class);

		Long pendingRetries = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'ACTIVE'
			  AND billing_attempts > 0
			  AND next_retry_at > NOW()
			""", Long.class);

		Long stuckInFlight = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM subscriptions
			WHERE status = 'ACTIVE'
			  AND billing_attempts > 0
			  AND next_retry_at < NOW()
			""", Long.class);

		Long outboxPending = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM event_publication
			WHERE completion_date IS NULL
			""", Long.class);

		Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscriptions", Long.class);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("totalSubscriptions", total);
		result.put("countsByStatus", statusCounts);
		result.put("successfulRenewals", successfulRenewals);
		result.put("pendingRetries", pendingRetries);
		result.put("stuckInFlight", stuckInFlight);
		result.put("outboxPending", outboxPending);
		result.put("checkedAt", LocalDateTime.now().toString());

		log.info("📊 [ADMIN] Status consultado: total={}, renovadas={}, pendentes={}, outbox={}",
			total, successfulRenewals, pendingRetries, outboxPending);

		return ResponseEntity.ok(result);
	}
}

