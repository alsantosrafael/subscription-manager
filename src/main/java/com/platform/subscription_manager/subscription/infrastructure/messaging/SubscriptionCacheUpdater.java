package com.platform.subscription_manager.subscription.infrastructure.messaging;

import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class SubscriptionCacheUpdater {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionCacheUpdater.class);
	private static final Duration NO_ACCESS_TTL = Duration.ofHours(1);
	private static final Duration ACTIVE_TTL_BUFFER = Duration.ofHours(2);
	private final RedisTemplate<String, Object> redisTemplate;

	public SubscriptionCacheUpdater(RedisTemplate<String, Object> redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	/**
	 * Fires only after the DB transaction that published this event has committed durably.
	 * Guarantees Redis is never ahead of the DB — a rolled-back transaction never
	 * contaminates the cache.
	 *
	 * Publishers must call eventPublisher.publishEvent() from WITHIN an active transaction
	 * for AFTER_COMMIT to fire. See SubscriptionWriteService and RenewalOrchestratorService.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onSubscriptionUpdated(SubscriptionUpdatedEvent event) {
		log.info("💾 [CACHE] Atualizando Redis para user {} / sub {}", event.userId(), event.id());
		String cacheKey = "subscription:user:" + event.userId();

		try {
			Duration ttl = resolveTtl(event);
			redisTemplate.opsForValue().set(cacheKey, event, ttl);
			log.info("✅ [CACHE] Redis atualizado → key='{}' status={} ttl={}min",
				cacheKey, event.status(), ttl.toMinutes());
		} catch (Exception e) {
			log.error("❌ Falha ao realizar write-through no Redis para a sub {}", event.id(), e);
		}
	}

	/**
	 * TTL strategy — keeps cache consistent without thundering herd:
	 *
	 * ACTIVE   → expiringDate − now + 2h buffer. The entry stays warm through the renewal
	 *            sweep; the result event will refresh it before it expires.
	 *
	 * CANCELED → same as ACTIVE. The user still has access until expiringDate; the scheduler
	 *            will publish an INACTIVE event when the period ends and overwrite this entry.
	 *
	 * SUSPENDED → 1h. Access is revoked; entry acts as a negative cache to absorb reads
	 *             without DB hits. Short TTL because the user may reactivate soon.
	 *
	 * INACTIVE  → 1h. Relationship is fully terminated; same negative-cache reasoning.
	 */
	private Duration resolveTtl(SubscriptionUpdatedEvent event) {
		return switch (event.status()) {
			case ACTIVE, CANCELED -> computeRemainingTtl(event.expiringDate());
			case SUSPENDED, INACTIVE -> NO_ACCESS_TTL;
		};
	}

	private Duration computeRemainingTtl(LocalDateTime expiringDate) {
		if (expiringDate == null) {
			return NO_ACCESS_TTL;
		}
		Duration remaining = Duration.between(LocalDateTime.now(), expiringDate);
		if (remaining.isNegative()) {
			// Already past expiry — short TTL, next sweep will fix it
			return Duration.ofMinutes(10);
		}
		return remaining.plus(ACTIVE_TTL_BUFFER);
	}
}
