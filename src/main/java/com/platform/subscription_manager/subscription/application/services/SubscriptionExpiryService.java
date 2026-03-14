package com.platform.subscription_manager.subscription.application.services;

import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.subscription.domain.projections.ExpiringSubscriptionRow;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Scheduler responsible for transitioning expired CANCELED subscriptions to INACTIVE.
 *
 * <p>Runs independently from {@link RenewalOrchestratorService} under its own
 * {@code @SchedulerLock} name ({@code "expirySweepTask"}), so a slow expiry cycle
 * never delays the billing renewal sweep — and vice versa.
 *
 * <p>Processes rows in pages of {@value BATCH_SIZE} (always page 0): after each
 * UPDATE the affected rows leave the result set, so the next query to page 0
 * naturally returns the following batch without skipping any rows.
 */
@Service
public class SubscriptionExpiryService {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryService.class);
	static final int BATCH_SIZE = 500;
	private static final long MAX_EXECUTION_TIME_MS = 45_000;

	private final SubscriptionRepository repository;
	private final ApplicationEventPublisher eventPublisher;

	public SubscriptionExpiryService(SubscriptionRepository repository,
									 ApplicationEventPublisher eventPublisher) {
		this.repository = repository;
		this.eventPublisher = eventPublisher;
	}

	@Scheduled(cron = "0 * * * * *")
	@SchedulerLock(name = "expirySweepTask", lockAtMostFor = "55s", lockAtLeastFor = "1s")
	public void runExpirySweep() {
		log.info("🗓️ [EXPIRY] Iniciando varredura de expiração...");
		int total = expireCanceledSubscriptions();
		log.info("🗓️ [EXPIRY] {} assinatura(s) movida(s) para INACTIVE neste ciclo.", total);
	}

	public int expireCanceledSubscriptions() {
		int totalExpired = 0;
		long startTime = System.currentTimeMillis();

		while (true) {
			if (System.currentTimeMillis() - startTime > MAX_EXECUTION_TIME_MS) {
				log.warn("⚠️ [EXPIRY] Tempo limite atingido. Interrompendo para liberar recursos.");
				break;
			}

			List<ExpiringSubscriptionRow> batch = repository.findExpiringSubscriptions();
			if (batch.isEmpty()) break;

			int updated = repository.expireCanceledSubscriptionsBatch(BATCH_SIZE);
			
			if (updated > 0) {
				totalExpired += updated;
				batch.stream()
					.limit(updated)
					.forEach(this::publishExpiryEvent);
			}

			if (updated < batch.size()) break;
		}

		return totalExpired;
	}

	private void publishExpiryEvent(ExpiringSubscriptionRow row) {
		log.info("🔕 [EXPIRY] Sub {} (user {}, plano {}) → INACTIVE. Expirou em {}.",
			row.id(), row.userId(), row.plan(), row.expiringDate());
		eventPublisher.publishEvent(new SubscriptionUpdatedEvent(
				row.id(),
				row.userId(),
				SubscriptionStatus.INACTIVE,
				row.plan(),
				row.startDate(),
				row.expiringDate(),
			false
		));
	}
}
