package com.platform.subscription_manager.shared.config;


import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka Topic Configuration
 * 
 * DEV ENVIRONMENT:
 * - Replicas: 1 (single-node Kafka in docker-compose)
 * - Min In-Sync Replicas (minIsr): 1
 * 
 * PRODUCTION ENVIRONMENT (3+ brokers):
 * - Replicas: 2 or 3
 * - Min In-Sync Replicas (minIsr): 2 (ensures durability)
 * 
 * Note: Update broker count in docker-compose and adjust these values accordingly
 */
@Configuration
public class KafkaTopicConfig {

	/**
	 * subscription.renewals topic
	 * - Producer: RenewalEventDispatcher
	 * - Consumer: BillingWorker (group: billing-processor-group)
	 * - Partitions: 10 (allows parallel processing with billing workers)
	 * - Replicas: 1 (dev) - increase to 2+ for production
	 */
	@Bean
	public NewTopic subscriptionRenewals() {
		return TopicBuilder.name("subscription.renewals")
			.partitions(10)
			.replicas(1)
			.config("min.insync.replicas", "1")
			.config("retention.ms", String.valueOf(7 * 24 * 60 * 60 * 1000))  // 7 days
			.config("segment.ms", String.valueOf(24 * 60 * 60 * 1000))  // 24 hours
			.config("cleanup.policy", "delete")
			.config("compression.type", "snappy")
			.build();
	}

	/**
	 * subscription.billing-results topic
	 * - Producer: BillingResultEventDispatcher
	 * - Consumer: SubscriptionResultListener (group: subscription-updater-group)
	 * - Partitions: 3 (aligns with listener concurrency)
	 * - Replicas: 1 (dev) - increase to 2+ for production
	 */
	@Bean
	public NewTopic billingResults() {
		return TopicBuilder.name("subscription.billing-results")
			.partitions(3)
			.replicas(1)
			.config("min.insync.replicas", "1")
			.config("retention.ms", String.valueOf(7 * 24 * 60 * 60 * 1000))  // 7 days
			.config("segment.ms", String.valueOf(24 * 60 * 60 * 1000))  // 24 hours
			.config("cleanup.policy", "delete")
			.config("compression.type", "snappy")
			.build();
	}

}
