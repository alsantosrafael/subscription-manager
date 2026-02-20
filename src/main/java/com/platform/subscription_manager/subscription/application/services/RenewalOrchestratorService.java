package com.platform.subscription_manager.subscription.application.services;


import com.platform.subscription_manager.subscription.RenewalRequestedEvent;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import com.platform.subscription_manager.subscription.domain.enums.SubscriptionStatus;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class RenewalOrchestratorService {

	private static final Logger log = LoggerFactory.getLogger(RenewalOrchestratorService.class);

	@Value("${billing.retry.max-attempts:3}")
	private int maxAttempts;

	@Value("${billing.retry.delay-hours:4}")
	private int retryDelayHours;

	private final SubscriptionRepository repository;
	private final ApplicationEventPublisher eventPublisher;

	public RenewalOrchestratorService(SubscriptionRepository repository, ApplicationEventPublisher eventPublisher) {
		this.repository = repository;
		this.eventPublisher = eventPublisher;
	}

	// Midnight ("0 0 0 * * *")
	@Scheduled(cron = "0 * * * * *") // Every single minute
	@SchedulerLock(name = "dailySweepTask", lockAtMostFor = "10m", lockAtLeastFor = "30s")
	@Transactional
	public void executeDailySweep() {
		log.info("Iniciando varredura do Scheduler (Lock adquirido)...");
		LocalDateTime now = LocalDateTime.now();

		// 1. Handle canceled subscriptions
		int expiredCount = repository.expireCanceledSubscriptions(now);
		if (expiredCount > 0) {
			log.info("Revogado o acesso de {} assinaturas que atingiram a data limite.", expiredCount);
		}

		// 2. Batch processing for the renewals
		processPendingRenewals(now);
		log.info("Varredura do Scheduler concluída.");
	}

	private void processPendingRenewals(LocalDateTime now) {
		Pageable page = PageRequest.of(0, 500);
		Slice<Subscription> slice;
		int billingEventsCount = 0;

		LocalDateTime thresholdTime = now.minusHours(retryDelayHours);

		do {
			slice = repository.findEligibleForRenewal(
				SubscriptionStatus.ACTIVE,
				now,
				thresholdTime,
				maxAttempts,
				page
			);

			for (Subscription sub : slice.getContent()) {
				sub.markBillingAttempt();
				eventPublisher.publishEvent(new RenewalRequestedEvent(
					sub.getId(),
					sub.getPlan(),
					sub.getPaymentToken(),
					sub.getExpiringDate(),
					sub.getBillingAttempts() + 1
				));
				billingEventsCount++;
			}
			page = slice.nextPageable();

		} while (slice.hasNext());

		if (billingEventsCount > 0) {
			log.info("Emitidos {} eventos de cobrança para o Event Registry.", billingEventsCount);
		}
	}
}