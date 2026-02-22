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
			    s.billing_attempts,
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
			    s.billing_attempts DESC
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
}
