package com.platform.subscription_manager.shared.config;


import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

	@Bean
	public NewTopic subscriptionRenewals() {
		return TopicBuilder.name("subscription.renewals")
			.partitions(10)
			.replicas(1)
			.build();
	}

	@Bean
	public NewTopic billingResults() {
		return TopicBuilder.name("subscription.billing-results")
			.partitions(3)
			.replicas(1)
			.build();
	}

}
