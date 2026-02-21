package com.platform.subscription_manager.shared.infrastructure.messaging;


import com.platform.subscription_manager.subscription.domain.enums.Plan;
import java.time.LocalDateTime;
import java.util.UUID;

public record RenewalRequestedEvent(
	UUID subscriptionId,
	Plan plan,
	String paymentToken,
	LocalDateTime expiringDate,
	int currentAttempt
) {}