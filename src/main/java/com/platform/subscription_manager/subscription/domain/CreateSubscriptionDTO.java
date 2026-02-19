package com.platform.subscription_manager.subscription.domain;

import com.platform.subscription_manager.subscription.domain.enums.Plan;

import java.util.UUID;

public record CreateSubscriptionDTO(
	UUID userId,
	Plan plan,
	String paymentToken
) {
}
