package com.platform.subscription_manager.subscription.application.services;


import com.platform.subscription_manager.shared.infrastructure.messaging.RenewalRequestedEvent;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Service
public class RenewalOrchestratorService {

	private static final Logger log = LoggerFactory.getLogger(RenewalOrchestratorService.class);

	@Value("${billing.retry.max-attempts:3}")
	private int maxAttempts;

	@Value("${billing.retry.base-delay-minutes:60}")
	private int baseDelayMinutes;

	private final SubscriptionRepository repository;
	private final ApplicationEventPublisher eventPublisher;
	private final TransactionTemplate transactionTemplate;

	public RenewalOrchestratorService(SubscriptionRepository repository, ApplicationEventPublisher eventPublisher, TransactionTemplate transactionTemplate) {
		this.repository = repository;
		this.eventPublisher = eventPublisher;
		this.transactionTemplate = transactionTemplate;
	}

	@Scheduled(cron = "0 * * * * *")
	@SchedulerLock(name = "dailySweepTask", lockAtMostFor = "10m", lockAtLeastFor = "30s")

	public void executeDailySweep() {
		log.info("Iniciando varredura do Scheduler...");
		LocalDateTime now = LocalDateTime.now();

		int expiredCount = repository.expireCanceledSubscriptions(now);
		if (expiredCount > 0) {
			log.info("Acesso revogado para {} assinaturas expiradas.", expiredCount);
		}

		processPendingRenewals(now);
		log.info("Varredura concluída.");
	}

	private void processPendingRenewals(LocalDateTime now) {
		Pageable page = PageRequest.of(0, 500);

		Slice<Subscription> slice = repository.findEligibleForRenewal(
			SubscriptionStatus.ACTIVE,
			now,
			maxAttempts,
			page
		);

		for (Subscription sub : slice.getContent()) {
			try {
				transactionTemplate.executeWithoutResult(status -> {
					// Re-fetch inside the transaction so the entity is managed, not detached
					Subscription managed = repository.findById(sub.getId())
						.orElseThrow(() -> new IllegalStateException("Sub não encontrada durante renovação: " + sub.getId()));

					managed.markBillingAttempt(baseDelayMinutes);
					repository.save(managed);

					eventPublisher.publishEvent(new RenewalRequestedEvent(
						managed.getId(),
						managed.getPlan(),
						managed.getExpiringDate(),
						managed.getBillingAttempts()
					));
				});
			} catch (Exception e) {
				log.error("Erro ao despachar renovação para sub {}: {}", sub.getId(), e.getMessage());
			}
		}
	}
}