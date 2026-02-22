package com.platform.subscription_manager.subscription.application.services;

import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.subscription.application.dtos.CreateSubscriptionDTO;
import com.platform.subscription_manager.subscription.application.dtos.SubscriptionResponseDTO;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

	private final SubscriptionWriteService subscriptionWriteService;
	private final SubscriptionRepository subscriptionRepository;
	private final RedisTemplate<String, Object> redisTemplate;
	private final ApplicationEventPublisher eventPublisher;
	private final TransactionTemplate transactionTemplate;

	public SubscriptionResponseDTO create(CreateSubscriptionDTO payload) {
		SubscriptionUpdatedEvent cacheEvent = subscriptionWriteService.createAndCharge(payload);
		transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(cacheEvent));
		return new SubscriptionResponseDTO(
			cacheEvent.id(), cacheEvent.status(), cacheEvent.plan(),
			cacheEvent.startDate(), cacheEvent.expiringDate(), cacheEvent.autoRenew());
	}

	public void cancel(UUID subscriptionId, UUID userId) {
		SubscriptionUpdatedEvent cacheEvent = subscriptionWriteService.cancelSubscription(subscriptionId, userId);
		transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(cacheEvent));
		log.info("🚫 [ASSINATURA] Sub {} cancelada. Cache atualizado.", subscriptionId);
	}

	public Optional<SubscriptionResponseDTO> get(UUID subscriptionId, UUID userId) {
		String cacheKey = "subscription:user:" + userId;
		Object cachedObj = redisTemplate.opsForValue().get(cacheKey);

		if (cachedObj instanceof SubscriptionUpdatedEvent cachedEvent && cachedEvent.id().equals(subscriptionId)) {
			return Optional.of(new SubscriptionResponseDTO(
				cachedEvent.id(), cachedEvent.status(), cachedEvent.plan(),
				cachedEvent.startDate(), cachedEvent.expiringDate(), cachedEvent.autoRenew()));
		}

		return subscriptionRepository.findByIdAndUserId(subscriptionId, userId)
			.map(s -> new SubscriptionResponseDTO(
				s.getId(), s.getStatus(), s.getPlan(),
				s.getStartDate(), s.getExpiringDate(), s.isAutoRenew()));
	}
}
