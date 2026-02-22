package com.platform.subscription_manager.subscription.application.dtos;

import com.platform.subscription_manager.shared.domain.Plan;
import com.platform.subscription_manager.shared.domain.SubscriptionStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SubscriptionResponseDTO(
	UUID id,
	SubscriptionStatus status,
	Plan plan,
	LocalDateTime startDate,
	LocalDateTime expiringDate,
	boolean autoRenew
) {
}
