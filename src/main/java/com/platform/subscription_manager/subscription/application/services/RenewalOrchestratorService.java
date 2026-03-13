package com.platform.subscription_manager.subscription.application.services;


import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.shared.infrastructure.messaging.RenewalRequestedEvent;
import com.platform.subscription_manager.subscription.domain.projections.EligibleRenewalRow;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class RenewalOrchestratorService {

	private static final Logger log = LoggerFactory.getLogger(RenewalOrchestratorService.class);

	@Value("${billing.retry.max-attempts:3}")
	private int maxAttempts;

	@Value("${billing.inflight-guard-minutes:5}")
	private int inFlightGuardMinutes;

	private final SubscriptionRepository repository;
	private final ApplicationEventPublisher eventPublisher;
	private final TransactionTemplate transactionTemplate;

	public RenewalOrchestratorService(SubscriptionRepository repository, ApplicationEventPublisher eventPublisher, TransactionTemplate transactionTemplate) {
		this.repository = repository;
		this.eventPublisher = eventPublisher;
		this.transactionTemplate = transactionTemplate;
	}

	/**
	 * ShedLock is the first line of defense — prevents redundant work across pods in the
	 * normal case. {@code markBillingAttemptAtomic} is the second line — a CAS guard that
	 * guarantees correctness even if the lock is briefly bypassed (defense in depth).
	 * All time comparisons use DB clock (CURRENT_TIMESTAMP), consistent with ShedLockConfig.usingDbTime().
	 */
	@Scheduled(cron = "0 * * * * *")
	@SchedulerLock(name = "renewalSweepTask", lockAtMostFor = "55s", lockAtLeastFor = "1s")
	public void executeDailySweep() {
		log.info("📋 [RENEWAL] Iniciando varredura de renovações...");
		processPendingRenewals();
		log.info("📋 [RENEWAL] Varredura de renovações concluída.");
	}

	private void processPendingRenewals() {
		Slice<EligibleRenewalRow> slice;
		int page = 0;
		int totalDispatched = 0;
		do {
			slice = repository.findEligibleForRenewal(
				SubscriptionStatus.ACTIVE,
				maxAttempts,
				PageRequest.of(page, 500)
			);

			List<EligibleRenewalRow> eligible = slice.getContent();
			if (eligible.isEmpty()) break;

			log.info("📋 [RENEWAL] Processando página {} com {} subscrições elegíveis.", page, eligible.size());

			for (EligibleRenewalRow row : eligible) {
				// int[] used as a mutable capture: lambda 'return' only exits the lambda,
				// so we need a side-channel to know whether the CAS actually claimed the row.
				int[] claimedRef = {0};
				try {
					LocalDateTime nextRetryAt = LocalDateTime.now()
						.truncatedTo(ChronoUnit.MICROS)
						.plusMinutes(inFlightGuardMinutes);

					transactionTemplate.executeWithoutResult(status -> {
						int claimed = repository.markBillingAttemptAtomic(
							row.id(), row.billingAttempts(), nextRetryAt);

						if (claimed == 0) {
							log.debug("⏭️ [RENEWAL] Sub {} já reivindicada por outro pod — pulando.", row.id());
							return;
						}

						claimedRef[0] = 1;
						log.info("📤 [RENEWAL] Sub {} (user {}, plano {}) despachada para cobrança. Tentativa {}.",
							row.id(), row.userId(), row.plan(), row.billingAttempts());

						eventPublisher.publishEvent(new RenewalRequestedEvent(
							row.id(),
							row.plan(),
							row.expiringDate(),
							row.billingAttempts()
						));
					});
				} catch (Exception e) {
					log.error("Erro ao despachar renovação para sub {}: {}", row.id(), e.getMessage());
				}
				if (claimedRef[0] == 1) {
					totalDispatched++;
				}
			}

			page++;
		} while (slice.hasNext());

		log.info("📋 [RENEWAL] Total enviado no ciclo corrente: {} subscrições.", totalDispatched);
	}
}