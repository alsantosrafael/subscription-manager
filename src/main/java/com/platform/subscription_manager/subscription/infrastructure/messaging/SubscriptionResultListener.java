package com.platform.subscription_manager.subscription.infrastructure.messaging;

import com.platform.subscription_manager.shared.domain.exceptions.UnknownBillingResultStatusException;
import com.platform.subscription_manager.shared.infrastructure.messaging.BillingResultEvent;
import com.platform.subscription_manager.subscription.domain.BillingCyclePolicy;
import com.platform.subscription_manager.subscription.domain.entity.Subscription;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SubscriptionResultListener {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionResultListener.class);
	private final SubscriptionRepository repository;
	@Value("${billing.retry.max-attempts:3}")
	private int maxAttempts;

	public SubscriptionResultListener(SubscriptionRepository repository) {
		this.repository = repository;
	}

	@KafkaListener(topics = "subscription.billing-results", groupId = "subscription-updater-group")
	@Transactional
	public void onBillingResult(BillingResultEvent event) {
		log.info("📩 [SUBSCRIPTION] Resultado da cobrança recebido. Assinatura: {}. Sucesso: {}", event.subscriptionId(), event.status().name());

		Subscription sub = repository.findById(event.subscriptionId())
			.orElseThrow(() -> new IllegalArgumentException("Assinatura não encontrada: " + event.subscriptionId()));
		switch (event.status()) {
			case SUCCESS: {
				sub.registerBillingSuccess(BillingCyclePolicy.calculateNextExpiration(sub.getExpiringDate()));
				log.info("🎉 Assinatura renovada com sucesso! Próximo vencimento: {}", sub.getExpiringDate());
				repository.save(sub);
			};
			case FAILED: {
				sub.registerPaymentFailure(maxAttempts);
				log.warn("⚠️ Falha registrada. Tentativas atuais: {}/{}", sub.getBillingAttempts(), maxAttempts);
				if (sub.getBillingAttempts() >= maxAttempts) {
					log.error("🚫 Limite de tentativas atingido. Assinatura para user={} SUSPENSA", sub.getUserId());
				}
				repository.save(sub);
			}
			default:
				log.error("⚠️ Status desconhecido enviado pelo processamento de assinatura status={}, subscription={} user={}", event.status(), event.subscriptionId(), sub.getUserId());
				throw new UnknownBillingResultStatusException("Status desconhecido para processamento de resposta de cobrança por assinatura");
		}
	}
}