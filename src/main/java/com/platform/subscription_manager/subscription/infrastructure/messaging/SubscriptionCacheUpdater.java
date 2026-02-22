package com.platform.subscription_manager.subscription.infrastructure.messaging;

import com.platform.subscription_manager.shared.infrastructure.messaging.SubscriptionUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class SubscriptionCacheUpdater {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionCacheUpdater.class);
	private final RedisTemplate<String, Object> redisTemplate;

	public SubscriptionCacheUpdater(RedisTemplate<String, Object> redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	// Event is published post-commit by SubscriptionResultListener — plain @EventListener is correct here
	@EventListener
	public void onSubscriptionUpdated(SubscriptionUpdatedEvent event) {
		log.info("💾 [CACHE] Atualizando Redis para user {} / sub {}", event.userId(), event.id());

		String cacheKey = "subscription:user:" + event.userId();

		try {
			redisTemplate.opsForValue().set(cacheKey, event, Duration.ofDays(31));
			log.info("✅ [CACHE] Redis atualizado → key='{}' status={} expiry={}", cacheKey, event.status(), event.expiringDate());
		} catch (Exception e) {
			log.error("❌ Falha ao realizar write-through no Redis para a sub {}", event.id(), e);
		}
	}
}
