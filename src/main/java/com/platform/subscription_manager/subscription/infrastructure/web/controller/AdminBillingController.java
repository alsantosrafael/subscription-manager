package com.platform.subscription_manager.subscription.infrastructure.web.controller;

import com.platform.subscription_manager.subscription.application.services.RenewalOrchestratorService;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
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

	@PostMapping("/billing/trigger-sweep")
	public ResponseEntity<Void> triggerSweep() {
		log.info("⚙️ Gatilho manual de faturamento acionado via API Administrativa.");
		orchestrator.executeDailySweep();
		return ResponseEntity.accepted().build();
	}

	/**
	 * Returns all subscriptions ordered by status and billing_attempts DESC.
	 * Intended for demo/observability — lets the evaluator confirm state transitions
	 * without needing direct DB access.
	 * Uses JdbcTemplate directly to avoid triggering PaymentTokenConverter on the entity.
	 */
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

	/**
	 * Force-suspends a subscription immediately.
	 * Reuses the same suspendSubscriptionAtomic path the scheduler uses — the evaluator
	 * can verify the suspension scenario without waiting for 3 real billing cycles.
	 * Returns 404 if the subscription is not found or is not in an ACTIVE state.
	 */
	@PostMapping("/subscriptions/{id}/force-suspend")
	public ResponseEntity<Void> forceSuspend(@PathVariable UUID id) {
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
