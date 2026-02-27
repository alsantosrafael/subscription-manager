package com.platform.subscription_manager.subscription.infrastructure.messaging;

import com.platform.subscription_manager.shared.domain.BillingHistoryStatus;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;
import com.platform.subscription_manager.shared.infrastructure.messaging.BillingResultEvent;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.subscription.domain.BillingCyclePolicy;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Component
public class SubscriptionResultListener {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionResultListener.class);
	private final SubscriptionRepository repository;
	private final TransactionTemplate transactionTemplate;
	private final ApplicationEventPublisher eventPublisher; // Para disparar o update do cache

	@Value("${billing.retry.max-attempts:3}")
	private int maxAttempts;

	@Value("${billing.retry.base-delay-minutes:60}")
	private int baseDelayMinutes;

	public SubscriptionResultListener(SubscriptionRepository repository,
									  TransactionTemplate transactionTemplate,
									  ApplicationEventPublisher eventPublisher) {
		this.repository = repository;
		this.transactionTemplate = transactionTemplate;
		this.eventPublisher = eventPublisher;
	}

	@KafkaListener(topics = "subscription.billing-results", groupId = "subscription-updater-group", batch = "true")
	public void onBillingResult(List<ConsumerRecord<String, BillingResultEvent>> records, Acknowledgment ack) {
		log.info("📥 [SUBSCRIPTION] Processando lote de {} resultados.", records.size());
		List<SubscriptionUpdatedEvent> cacheEvents = new ArrayList<>();

		try {
			transactionTemplate.executeWithoutResult(status -> {
				for (var record : records) {
					processSingleResult(record.value(), cacheEvents);
				}
				cacheEvents.forEach(eventPublisher::publishEvent);
			});
			ack.acknowledge();
			log.info("✅ [SUBSCRIPTION] Lote de {} resultados processado e commitado.", records.size());
		} catch (Exception ex) {
			log.error("❌ Falha no lote de atualização. O Kafka fará o retry.", ex);
			throw ex;
		}
	}

	private void processSingleResult(BillingResultEvent event, List<SubscriptionUpdatedEvent> cacheEvents) {
		var maybeSubscription = repository.findById(event.subscriptionId());
		if (maybeSubscription.isEmpty()) {
			log.warn("⚠️ [SUBSCRIPTION] Ignorando resultado para sub inexistente {} (mensagem obsoleta do Kafka).", event.subscriptionId());
			return;
		}
		Subscription sub = maybeSubscription.get();

		if (!sub.getExpiringDate().isEqual(event.referenceExpiringDate())) {
			log.warn("🔄 Ignorando evento obsoleto para sub {}", event.subscriptionId());
			return;
		}

		if (BillingHistoryStatus.SUCCESS.equals(event.status())) {
			handleSuccess(sub, event, cacheEvents);
		} else {
			handleFailure(sub, event, cacheEvents);
		}
	}

	private void handleSuccess(Subscription sub, BillingResultEvent event, List<SubscriptionUpdatedEvent> cacheEvents) {
		LocalDateTime nextDate = BillingCyclePolicy.calculateNextExpiration(event.referenceExpiringDate());

		int updated = repository.renewSubscriptionAtomic(sub.getId(), event.referenceExpiringDate(), nextDate);

		if (updated == 1) {
			// Reflect what the atomic UPDATE wrote — no extra DB round-trip needed
			sub.applyRenewal(nextDate);
			log.info("🎉 Sub {} renovada até {}", sub.getId(), nextDate);
			cacheEvents.add(new SubscriptionUpdatedEvent(sub.getId(), sub.getUserId(), sub.getStatus(), sub.getPlan(), sub.getStartDate(), sub.getExpiringDate(), sub.isAutoRenew()));
		}
	}

	private void handleFailure(Subscription sub, BillingResultEvent event, List<SubscriptionUpdatedEvent> cacheEvents) {
		LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
		int currentAttempts = sub.getBillingAttempts();
		int attemptsAfter = currentAttempts + 1;

		SubscriptionStatus resultingStatus;
		boolean resultingAutoRenew;

		if (attemptsAfter >= maxAttempts) {
			repository.suspendSubscriptionAtomic(sub.getId(), event.referenceExpiringDate(), now, currentAttempts);
			resultingStatus = SubscriptionStatus.SUSPENDED;
			resultingAutoRenew = false;
			log.warn("🔴 [BILLING] Sub {} SUSPENSA após {} falhas consecutivas. autoRenew=false. Requer reativação manual.",
				sub.getId(), attemptsAfter);
		} else {
			long delayMinutes = (long) Math.pow(2, attemptsAfter) * baseDelayMinutes;
			LocalDateTime nextRetryAt = now.plusMinutes(delayMinutes);
			repository.incrementFailureAtomic(sub.getId(), event.referenceExpiringDate(), now, nextRetryAt, currentAttempts);
			resultingStatus = SubscriptionStatus.ACTIVE;
			resultingAutoRenew = true;
			log.warn("⚠️ Sub {} falhou ({} tentativa(s)). Próxima tentativa em: {}", sub.getId(), attemptsAfter, nextRetryAt);
		}

		cacheEvents.add(new SubscriptionUpdatedEvent(
			sub.getId(), sub.getUserId(), resultingStatus,
			sub.getPlan(), sub.getStartDate(), sub.getExpiringDate(), resultingAutoRenew));
	}
}
