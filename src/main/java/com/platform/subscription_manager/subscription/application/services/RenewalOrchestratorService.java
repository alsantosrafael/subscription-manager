package com.platform.subscription_manager.subscription.application.services;


import com.platform.subscription_manager.shared.domain.Plan;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.shared.infrastructure.messaging.RenewalRequestedEvent;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
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
import java.util.UUID;

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

	@Scheduled(cron = "0 * * * * *")
	@SchedulerLock(name = "dailySweepTask", lockAtMostFor = "55s", lockAtLeastFor = "1s")
	public void executeDailySweep() {
		log.info("Iniciando varredura do Scheduler...");
		LocalDateTime now = LocalDateTime.now();

		transactionTemplate.executeWithoutResult(txStatus -> {
			List<Object[]> expiring = repository.findExpiringSubscriptionIds(now);
			int expiredCount = repository.expireCanceledSubscriptions(now);
			if (expiredCount > 0) {
				log.info("🗓️ [SWEEP] {} assinatura(s) expirada(s) movida(s) para INACTIVE.", expiredCount);
				expiring.forEach(row -> {
					UUID subId            = (UUID) row[0];
					UUID userId           = (UUID) row[1];
					Plan plan             = (Plan) row[2];
					LocalDateTime start   = (LocalDateTime) row[3];
					LocalDateTime expires = (LocalDateTime) row[4];
					log.info("🔕 [SWEEP] Sub {} (user {}, plano {}) → INACTIVE. Expirou em {}.",
						subId, userId, plan, expires);
					eventPublisher.publishEvent(new SubscriptionUpdatedEvent(
						subId, userId, SubscriptionStatus.INACTIVE, plan, start, expires, false));
				});
			}
		});

		processPendingRenewals(now);
		log.info("Varredura concluída.");
	}

	private void processPendingRenewals(LocalDateTime now) {
		LocalDateTime truncatedNow = now.truncatedTo(ChronoUnit.MICROS);
		Slice<UUID> slice;
		int page = 0;
		int totalDispatched = 0;
		do {
			slice = repository.findEligibleForRenewal(
				SubscriptionStatus.ACTIVE,
				truncatedNow,
				maxAttempts,
				PageRequest.of(page, 500)
			);

			List<UUID> eligible = slice.getContent();
			if (eligible.isEmpty()) {
				break;
			}

			log.info("📋 [RENEWAL] Processando página {} com {} subscrições elegíveis.", page, eligible.size());

			for (UUID id : eligible) {
				try {
					transactionTemplate.executeWithoutResult(status -> {
						Subscription managed = repository.findById(id)
							.orElseThrow(() -> new IllegalStateException("Sub não encontrada durante renovação: " + id));

						managed.markBillingAttempt(inFlightGuardMinutes);
						repository.save(managed);

						log.info("📤 [RENEWAL] Sub {} (user {}, plano {}) despachada para cobrança. Tentativa {}.",
							managed.getId(), managed.getUserId(), managed.getPlan(), managed.getBillingAttempts());

						eventPublisher.publishEvent(new RenewalRequestedEvent(
							managed.getId(),
							managed.getPlan(),
							managed.getExpiringDate(),
							managed.getBillingAttempts()
						));
					});
					totalDispatched++;
				} catch (Exception e) {
					log.error("Erro ao despachar renovação para sub {}: {}", id, e.getMessage());
				}
			}

			page++;
		} while (slice.hasNext());

		log.info("📋 [RENEWAL] Total enviado no ciclo corrente: {} subscrições.", totalDispatched);
	}
}