package com.platform.subscription_manager.shared.infrastructure.messaging;


import com.platform.subscription_manager.subscription.domain.enums.Plan;
import org.springframework.modulith.events.Externalized;

import java.time.LocalDateTime;
import java.util.UUID;

@Externalized("subscription.renewals")
public record RenewalRequestedEvent(
	UUID subscriptionId,
	Plan plan,
	String paymentToken,
	LocalDateTime expiringDate,
	int currentAttempt
) {}