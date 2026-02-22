package com.platform.subscription_manager.subscription.application.services;

import com.platform.subscription_manager.shared.domain.exceptions.ResourceNotFoundException;
import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import com.platform.subscription_manager.subscription.application.dtos.CreateSubscriptionDTO;
import com.platform.subscription_manager.subscription.application.dtos.SubscriptionResponseDTO;
import com.platform.subscription_manager.subscription.domain.repositories.SubscriptionRepository;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	/**
	 * Creates a subscription and charges the gateway synchronously.
	 *
	 * The transactional DB work is delegated to SubscriptionWriteService so that
	 * publishEvent() is only called after the transaction commits — the same
	 * post-commit guarantee the async SubscriptionResultListener provides manually.
	 * This prevents Redis from ever holding data that the DB rolled back.
	 */
	@Retry(name = "initialCharge")
	public SubscriptionResponseDTO create(CreateSubscriptionDTO payload) {
		SubscriptionUpdatedEvent cacheEvent = subscriptionWriteService.createAndCharge(payload);
		// Transaction committed — safe to write cache now
		eventPublisher.publishEvent(cacheEvent);
		log.info("✅ [ASSINATURA] Cache atualizado para sub {} user {}.", cacheEvent.id(), cacheEvent.userId());

		return new SubscriptionResponseDTO(
			cacheEvent.id(),
			cacheEvent.status(),
			cacheEvent.plan(),
			cacheEvent.startDate(),
			cacheEvent.expiringDate(),
			cacheEvent.autoRenew()
		);
	}

	@Transactional
	public void cancel(UUID subscriptionId, UUID userId) {
		var subscription = subscriptionRepository.findByIdAndUserId(subscriptionId, userId)
			.orElseThrow(() -> new ResourceNotFoundException("Subscription not found or belongs to another user"));

		subscription.cancelRenewal();

		subscriptionRepository.save(subscription);
	}

	public Optional<SubscriptionResponseDTO> get(UUID subscriptionId, UUID userId) {
		String cacheKey = "subscription:user:" + userId;
		Object cachedObj = redisTemplate.opsForValue().get(cacheKey);

		// Cache key is already scoped to userId, so a hit here is inherently owned by this user
		if (cachedObj instanceof SubscriptionUpdatedEvent cachedEvent && cachedEvent.id().equals(subscriptionId)) {
			return Optional.of(new SubscriptionResponseDTO(
				cachedEvent.id(),
				cachedEvent.status(),
				cachedEvent.plan(),
				cachedEvent.startDate(),
				cachedEvent.expiringDate(),
				cachedEvent.autoRenew()
			));
		}

		// Cache miss — enforce ownership at the DB level with findByIdAndUserId
		return subscriptionRepository.findByIdAndUserId(subscriptionId, userId)
			.map(sub -> new SubscriptionResponseDTO(
				sub.getId(),
				sub.getStatus(),
				sub.getPlan(),
				sub.getStartDate(),
				sub.getExpiringDate(),
				sub.isAutoRenew()
			));
	}

}
