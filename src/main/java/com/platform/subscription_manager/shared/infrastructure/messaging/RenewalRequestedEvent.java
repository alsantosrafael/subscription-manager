package com.platform.subscription_manager.shared.infrastructure.messaging;

import com.platform.subscription_manager.shared.domain.Plan;

import java.time.LocalDateTime;
import java.util.UUID;

public record RenewalRequestedEvent(
	UUID subscriptionId,
	Plan plan,
	LocalDateTime expiringDate,
	int currentAttempt
) {}