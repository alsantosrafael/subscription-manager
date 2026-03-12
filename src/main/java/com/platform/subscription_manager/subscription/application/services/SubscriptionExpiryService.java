package com.platform.subscription_manager.subscription.application.services;

import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.subscription.domain.projections.ExpiringSubscriptionRow;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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

	private final SubscriptionRepository repository;
	private final ApplicationEventPublisher eventPublisher;
	private final TransactionTemplate transactionTemplate;

	public SubscriptionExpiryService(SubscriptionRepository repository,
									 ApplicationEventPublisher eventPublisher,
									 TransactionTemplate transactionTemplate) {
		this.repository = repository;
		this.eventPublisher = eventPublisher;
		this.transactionTemplate = transactionTemplate;
	}

	@Scheduled(cron = "0 * * * * *")
	@SchedulerLock(name = "expirySweepTask", lockAtMostFor = "55s", lockAtLeastFor = "1s")
	public void runExpirySweep() {
		log.info("🗓️ [EXPIRY] Iniciando varredura de expiração...");
		int total = expireCanceledSubscriptions(LocalDateTime.now());
		log.info("🗓️ [EXPIRY] {} assinatura(s) movida(s) para INACTIVE neste ciclo.", total);
		log.info("🗓️ [EXPIRY] Varredura concluída.");
	}

	/**
	 * Core loop — public so the admin controller can trigger it on-demand and read the count.
	 * Unit tests can also drive it directly without going through the scheduler annotation.
	 *
	 * @return total number of rows transitioned to INACTIVE in this run
	 */
	public int expireCanceledSubscriptions(LocalDateTime now) {
		int totalExpired = 0;
		int batchCount;
		do {
			Integer result = transactionTemplate.execute(txStatus -> {
				Slice<ExpiringSubscriptionRow> slice = repository.findExpiringSubscriptionIds(
						now, PageRequest.of(0, BATCH_SIZE));

				List<ExpiringSubscriptionRow> rows = slice.getContent();
				if (rows.isEmpty()) return 0;

				List<UUID> ids = rows.stream().map(ExpiringSubscriptionRow::id).toList();
				int updated = repository.expireCanceledSubscriptionsByIds(ids);

				if (updated > 0) {
					rows.forEach(this::publishExpiryEvent);
				}
				return updated;
			});

			batchCount = result != null ? result : 0;
			totalExpired += batchCount;

		} while (batchCount > 0);

		return totalExpired;
	}

	private void publishExpiryEvent(ExpiringSubscriptionRow row) {
		log.info("🔕 [EXPIRY] Sub {} (user {}, plano {}) → INACTIVE. Expirou em {}.",
				row.id(), row.userId(), row.plan(), row.expiringDate());
		eventPublisher.publishEvent(
				new SubscriptionUpdatedEvent(row.id(), row.userId(), SubscriptionStatus.INACTIVE,
						row.plan(), row.startDate(), row.expiringDate(), false));
	}
}

