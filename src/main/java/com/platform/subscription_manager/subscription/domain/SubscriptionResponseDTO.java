package com.platform.subscription_manager.subscription.domain;

import com.platform.subscription_manager.subscription.domain.enums.Plan;
import com.platform.subscription_manager.subscription.domain.enums.SubscriptionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record SubscriptionResponseDTO(
	UUID id,
	SubscriptionStatus status,
	Plan plan,
	LocalDate startDate,
	LocalDateTime expiringDate,
	boolean autoRenew
) {
}
